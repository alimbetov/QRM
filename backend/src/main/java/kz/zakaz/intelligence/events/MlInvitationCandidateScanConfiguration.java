package kz.zakaz.intelligence.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

/** Internal discovery only; no external notifications or background SMS. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={
    "zakaz.ml.r2.invitation-evaluation.enabled",
    "zakaz.ml.r2.candidate-scanner.enabled"},havingValue="true")
@EnableScheduling
public class MlInvitationCandidateScanConfiguration {
    @Bean CandidateScanSchedule candidateScanSchedule(
            MlInvitationCandidateScanner scanner,
            @Value("${zakaz.ml.r2.candidate-scanner.batch-size:24}") int batchSize) {
        if(batchSize<1||batchSize>100)
            throw new IllegalArgumentException("Invalid ML candidate scanner batch size");
        return new CandidateScanSchedule(scanner,batchSize);
    }

    static final class CandidateScanSchedule {
        private static final Logger log=LoggerFactory.getLogger(CandidateScanSchedule.class);
        private final MlInvitationCandidateScanner scanner;
        private final int batchSize;
        CandidateScanSchedule(MlInvitationCandidateScanner scanner,int batchSize) {
            this.scanner=scanner;this.batchSize=batchSize;
        }
        @Scheduled(fixedDelayString="${zakaz.ml.r2.candidate-scanner.poll-delay-ms:5000}")
        void tick() {
            try {
                scanner.scanOnce(batchSize);
            }catch(RuntimeException failure) {
                // No SQL text, buyer identifiers, phone or contact details in logs.
                log.warn("ML invitation discovery failed: {}",
                    failure.getClass().getSimpleName());
            }
        }
    }
}
