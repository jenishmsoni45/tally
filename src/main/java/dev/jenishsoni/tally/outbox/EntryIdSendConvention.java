package dev.jenishsoni.tally.outbox;

import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation;

import dev.jenishsoni.tally.common.TelemetryKeys;
import io.micrometer.common.KeyValues;

/**
 * Tags each Kafka send span with the entry it carries, so a publish can be found by entry_id. The relay
 * keys every record by entry_id. High-cardinality, so it goes on the span only, never on a metric tag.
 */
class EntryIdSendConvention extends KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention {

    @Override
    public KeyValues getHighCardinalityKeyValues(KafkaRecordSenderContext context) {
        Object key = context.getRecord().key();
        return key == null ? KeyValues.empty() : KeyValues.of(TelemetryKeys.ENTRY_ID, key.toString());
    }

}
