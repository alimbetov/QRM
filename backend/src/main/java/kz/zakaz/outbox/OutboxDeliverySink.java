package kz.zakaz.outbox;

/** At-least-once transport; sinks MUST be idempotent per (consumer,eventId). */
@FunctionalInterface
public interface OutboxDeliverySink {
    void deliver(OutboxClaim claim);
}
