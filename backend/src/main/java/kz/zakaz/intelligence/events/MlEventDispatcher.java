package kz.zakaz.intelligence.events;

import java.time.Duration;
import java.util.Objects;

/** Bounded at-least-once poller: sink transaction commits before ACK. */
public class MlEventDispatcher {
    private final JdbcMlEventQueue queue;
    private final JdbcMlEventProjectionSink sink;
    private final String worker;
    private final int limit,maxAttempts;
    private final Duration lease;

    public MlEventDispatcher(JdbcMlEventQueue queue,JdbcMlEventProjectionSink sink,
        String worker,int limit,int maxAttempts,Duration lease) {
        this.queue=Objects.requireNonNull(queue);
        this.sink=Objects.requireNonNull(sink);
        if(worker==null||worker.isBlank()||worker.length()>120||
            limit<1||limit>100||maxAttempts<1||lease==null||
            lease.toMillis()<100||lease.toMillis()>300000)
            throw new IllegalArgumentException("Invalid ML delivery worker bounds");
        this.worker=worker;this.limit=limit;
        this.maxAttempts=maxAttempts;this.lease=lease;
    }

    /** One bounded cycle. Worker scheduling is OFF unless explicitly enabled. */
    public int pollOnce() {
        int acknowledged=0;
        for(var claim:queue.claim(worker,limit,lease,maxAttempts)) {
            try {
                sink.deliver(claim);
                if(queue.ack(claim)) acknowledged++;
            } catch(Exception ex) {
                long delay=Math.min(60000L,250L << Math.min(7,claim.attempts()-1));
                // Do not persist private exception text, tokens or SQL parameters.
                queue.fail(claim,ex.getClass().getSimpleName(),
                    Duration.ofMillis(delay),maxAttempts);
            }
        }
        return acknowledged;
    }
}
