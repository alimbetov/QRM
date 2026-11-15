package kz.zakaz.intelligence.batch;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BoundedBucketExecutorTest {
    @Test void executesAllBucketsOnceInStableOrderWithBoundedParallelism() {
        var active=new AtomicInteger();
        var peak=new AtomicInteger();
        var seen=java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var r=BoundedBucketExecutor.execute(new BoundedBucketExecutor.Config(16,2),i->{
            int n=active.incrementAndGet();
            peak.accumulateAndGet(n,Math::max);
            try {
                seen.add(i);
                try { Thread.sleep(4); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return i*i;
            } finally { active.decrementAndGet(); }
        });
        assertThat(r).hasSize(16);
        assertThat(r).extracting(BoundedBucketExecutor.BucketResult::bucket)
            .containsExactly(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
        assertThat(peak.get()).isLessThanOrEqualTo(2);
        assertThat(peak.get()).isGreaterThan(0);
        assertThat(seen).hasSize(16);
        assertThat(r.get(3).result()).isEqualTo(9);
    }
    @Test void deterministicBucketAssignmentAndInvalidConfigs() {
        var b=BoundedBucketExecutor.bucketFor("seller/sku/GEO.ALMATY",16);
        assertThat(b).isBetween(0,15);
        assertThat(BoundedBucketExecutor.bucketFor("seller/sku/GEO.ALMATY",16)).isEqualTo(b);
        assertThatThrownBy(()->new BoundedBucketExecutor.Config(16,0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new BoundedBucketExecutor.Config(1,2))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedBucketAbortsWholeComputationInsteadOfReturningPartialSnapshot() {
        assertThatThrownBy(()->BoundedBucketExecutor.execute(
            new BoundedBucketExecutor.Config(16,2),i->{
                if(i==4) throw new IllegalStateException("Test failure");
                return i;
            })).isInstanceOf(IllegalStateException.class)
               .hasMessageContaining("Failed bucket");
    }
}
