package kz.zakaz.outbox;

/** Operational counters, measured using PostgreSQL clock and persisted queue state. */
public record OutboxQueueHealth(long pending,long inFlight,long deadLetter,
                                long published,double oldestPendingSeconds) {}
