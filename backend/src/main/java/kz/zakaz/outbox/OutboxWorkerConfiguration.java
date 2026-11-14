package kz.zakaz.outbox;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/**
 * Only the explicit outbox-worker profile starts polling.
 * Default adapter is an INTERNAL PostgreSQL projection; no claim of external
 * delivery. Replace with a separately tested sink to integrate another system.
 */
@Configuration(proxyBeanMethods=false)
@Profile("outbox-worker")
@EnableScheduling
public class OutboxWorkerConfiguration {
    @Bean OutboxDeliverySink outboxDeliverySink(JdbcTemplate jdbc){
        return new JdbcOrderEventProjectionSink(jdbc);
    }
    @Bean OutboxDispatcher outboxDispatcher(JdbcOutboxQueue queue,OutboxDeliverySink sink,
      @Value("${zakaz.outbox.batch-size:10}") int batchSize,
      @Value("${zakaz.outbox.max-attempts:5}") int maxAttempts,
      @Value("${zakaz.outbox.lease-seconds:60}") long leaseSeconds) {
        return new OutboxDispatcher(queue,sink,UUID.randomUUID().toString(),
            batchSize,maxAttempts,Duration.ofSeconds(leaseSeconds));
    }
    @Bean OutboxPollingSchedule outboxPollingSchedule(OutboxDispatcher dispatcher) {
        return new OutboxPollingSchedule(dispatcher);
    }
    static final class OutboxPollingSchedule {
        private final OutboxDispatcher dispatcher;
        OutboxPollingSchedule(OutboxDispatcher dispatcher){this.dispatcher=dispatcher;}
        @Scheduled(fixedDelayString="${zakaz.outbox.poll-delay-ms:1000}")
        void tick(){dispatcher.pollOnce();}
    }
}
