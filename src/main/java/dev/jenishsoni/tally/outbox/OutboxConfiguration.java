package dev.jenishsoni.tally.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    // Spring Boot's auto-configured KafkaAdmin creates this topic at startup if it is missing.
    @Bean
    NewTopic ledgerEntriesTopic() {
        // Messages are keyed by entry_id, so Kafka orders them per entry only. Several partitions keep
        // that visible; with one, consumers could come to rely on a global order by accident.
        // One replica because the local and test brokers are single-node. A production cluster would
        // use 3 with min.insync.replicas=2, so that acks=all means more than the leader.
        return TopicBuilder.name(OutboxRelay.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

}
