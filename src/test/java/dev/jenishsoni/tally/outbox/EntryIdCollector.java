package dev.jenishsoni.tally.outbox;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Test-only reader of the entries topic. Delivery is at-least-once, so like a real consumer it keeps
 * the first copy of each entry id and counts the repeats it ignored. It is used only from the test
 * thread, because a Kafka consumer is not thread-safe.
 */
final class EntryIdCollector implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);

    private final Consumer<String, String> consumer;
    private final Map<UUID, String> payloads = new HashMap<>();
    private int duplicatesIgnored;

    private EntryIdCollector(Consumer<String, String> consumer) {
        this.consumer = consumer;
    }

    /**
     * Tests in the same Spring context share the topic, so the collector starts at the current end of
     * every partition and sees only what is published after it was created. It joins no consumer
     * group and never commits offsets, so it cannot move anyone else's position.
     */
    static EntryIdCollector fromNow(ConsumerFactory<String, String> consumers) {
        Properties overrides = new Properties();
        overrides.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        EntryIdCollector collector = new EntryIdCollector(
                consumers.createConsumer(null, "entry-id-collector", null, overrides));
        try {
            collector.startAtEndOfEveryPartition();
        } catch (RuntimeException e) {
            collector.close();
            throw e;
        }
        return collector;
    }

    /** Polls until every expected id has arrived or the timeout passes; the caller asserts on what arrived. */
    void awaitIds(Collection<UUID> expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!payloads.keySet().containsAll(expected) && System.nanoTime() - deadline < 0) {
            pollOnce();
        }
    }

    /** Reads everything published so far, so every repeat already in the topic is counted; fails after the timeout. */
    void readToEnd(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<TopicPartition, Long> ends = consumer.endOffsets(consumer.assignment(), timeout);
        while (!reached(ends)) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("Did not read to the end of " + OutboxRelay.TOPIC + " within " + timeout
                        + ": end offsets " + ends + ", positions " + positions(ends.keySet()));
            }
            pollOnce();
        }
    }

    Set<UUID> ids() {
        return Set.copyOf(payloads.keySet());
    }

    String payload(UUID entryId) {
        return payloads.get(entryId);
    }

    int duplicatesIgnored() {
        return duplicatesIgnored;
    }

    @Override
    public void close() {
        consumer.close();
    }

    private void startAtEndOfEveryPartition() {
        List<TopicPartition> partitions = consumer.partitionsFor(OutboxRelay.TOPIC, METADATA_TIMEOUT).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        if (partitions.isEmpty()) {
            throw new IllegalStateException("Topic " + OutboxRelay.TOPIC + " does not exist");
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd is lazy; asking for each position fixes the start now rather than at the first poll.
        partitions.forEach(partition -> consumer.position(partition, METADATA_TIMEOUT));
    }

    private void pollOnce() {
        for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
            if (payloads.putIfAbsent(UUID.fromString(record.key()), record.value()) != null) {
                duplicatesIgnored++;
            }
        }
    }

    private boolean reached(Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(end -> consumer.position(end.getKey()) >= end.getValue());
    }

    private Map<TopicPartition, Long> positions(Set<TopicPartition> partitions) {
        return partitions.stream().collect(Collectors.toMap(partition -> partition, consumer::position));
    }

}
