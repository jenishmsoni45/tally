## Problem

## Goals

## Non-goals

## Open questions

### Event ordering and delivery

Every posted entry is published once to the Kafka topic `ledger.entries`: the key is the entry_id and the value is the entry as the API returned it, lines and line ids included. The outbox stores the payload as jsonb, so the message has the same fields and values as the response but not necessarily the same key order or spacing; compare parsed JSON, not bytes.

- Kafka orders messages per entry, not per account. Order holds only within a partition, and the partition comes from the key, so two entries on the same account can land on different partitions and be read in either order. A consumer that needs one account's movements in order must sort them by the entry's line ids: lines are inserted only while the account's balance row is locked, so line ids on one account increase in commit order.
- Delivery is at-least-once. The relay marks rows published only after Kafka acknowledges them, and a batch whose acks or commit fail is sent again, so the same entry can arrive twice. Consumers dedupe on entry_id.
