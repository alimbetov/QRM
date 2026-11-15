package kz.zakaz.intelligence.events;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

/**
 * Internal-only event projection worker. This does not send emails or pushes.
 * Never auto-enable in dev/staging/prod; operator activates after S2 review.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="zakaz.ml.r2.delivery.enabled",havingValue="true")
@EnableScheduling
public class MlEventDeliveryConfiguration {
    @Bean MlEventDispatcher mlEventDispatcher(JdbcMlEventQueue queue,
        JdbcMlEventProjectionSink sink,
        @Value("${zakaz.ml.r2.delivery.batch-size:10}") int batchSize,
        @Value("${zakaz.ml.r2.delivery.max-attempts:5}") int maxAttempts,
        @Value("${zakaz.ml.r2.delivery.lease-seconds:60}") long leaseSeconds) {
        return new MlEventDispatcher(queue,sink,UUID.randomUUID().toString(),
            batchSize,maxAttempts,Duration.ofSeconds(leaseSeconds));
    }
    @Bean MlEventDeliverySchedule mlEventDeliverySchedule(MlEventDispatcher dispatcher) {
        return new MlEventDeliverySchedule(dispatcher);
    }
    static class MlEventDeliverySchedule {
        private final MlEventDispatcher dispatcher;
        MlEventDeliverySchedule(MlEventDispatcher dispatcher) {
            this.dispatcher=dispatcher;
        }
        @Scheduled(fixedDelayString="${zakaz.ml.r2.delivery.poll-delay-ms:1000}")
        void tick() {dispatcher.pollOnce();}
    }
}
