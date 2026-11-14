package kz.zakaz.outbox;

import java.util.*;

/** One lease claim, fenced by token and monotonically increasing generation. */
public record OutboxClaim(UUID id,UUID aggregateId,String eventType,String payload,
                          long sequence,long generation,UUID token,int attempts) {
    public OutboxClaim {
        Objects.requireNonNull(id);Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(eventType);Objects.requireNonNull(payload);Objects.requireNonNull(token);
        if(sequence<1||generation<1||attempts<1)
            throw new IllegalArgumentException("Invalid outbox claim");
    }
}
