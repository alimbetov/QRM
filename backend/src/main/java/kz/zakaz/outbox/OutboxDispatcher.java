package kz.zakaz.outbox;

import java.time.Duration;
import java.util.*;

/**
 * Bounded polling, no outer transaction across sink I/O.
 * Implementations own proper sink timeouts shorter than the lease.
 */
public final class OutboxDispatcher {
    private final JdbcOutboxQueue queue;
    private final OutboxDeliverySink sink;
    private final String workerId;
    private final int batchSize,maxAttempts;
    private final Duration lease;
    public OutboxDispatcher(JdbcOutboxQueue queue,OutboxDeliverySink sink,
            String workerId,int batchSize,int maxAttempts,Duration lease){
        this.queue=Objects.requireNonNull(queue);this.sink=Objects.requireNonNull(sink);
        if(workerId==null||workerId.isBlank()||workerId.length()>120
            ||batchSize<1||batchSize>100||maxAttempts<1||lease==null
            ||lease.toMillis()<100||lease.toMillis()>300000)
            throw new IllegalArgumentException("Invalid worker settings");
        this.workerId=workerId;this.batchSize=batchSize;
        this.maxAttempts=maxAttempts;this.lease=lease;
    }
    /** One bounded batch, driven only by a dedicated worker profile. */
    public int pollOnce(){
        var batch=queue.claim(workerId,batchSize,lease,maxAttempts);
        int acknowledged=0;
        for(var item:batch) {
            try {
                sink.deliver(item);
                if(queue.ack(item)) acknowledged++;
            } catch(Exception e){
                // At least once: ACK is only after sink has succeeded.
                long ms=Math.min(60000L,250L << Math.min(7,item.attempts()-1));
                queue.fail(item,e.getClass().getSimpleName(),Duration.ofMillis(ms),maxAttempts);
            }
        }
        return acknowledged;
    }
}
