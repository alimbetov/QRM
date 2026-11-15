package kz.zakaz.intelligence.batch;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Bounded in-process CPU/IO worker execution for smoke. This is NOT a distributed
 * scheduler, lease protocol, resumable job repository or atomic DB publisher.
 * Results are returned ONLY when all buckets succeed; caller persists a staged
 * generation and atomically promotes its manifest separately.
 */
public final class BoundedBucketExecutor {
    private BoundedBucketExecutor() {}
    public record Config(int bucketCount, int maxWorkers) {
        public Config {
            if (bucketCount < 1 || bucketCount > 65536 || maxWorkers < 1
                || maxWorkers > bucketCount || maxWorkers > 32)
                throw new IllegalArgumentException("Invalid worker/bucket limits");
        }
    }
    public record BucketResult<R>(int bucket, R result) {}

    /**
     * Stable deterministic keys must be canonical strings and preserve version of
     * serialization and hashing. This prototype uses SHA-256 first 4 bytes to avoid
     * Java Object.hashCode instability; bucket assignment must be versioned on disk.
     */
    public static int bucketFor(String canonicalKey, int bucketCount) {
        if (canonicalKey == null || canonicalKey.isBlank() || bucketCount < 1)
            throw new IllegalArgumentException("Invalid key/bucket count");
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonicalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int prefix=java.nio.ByteBuffer.wrap(hash,0,4).getInt();
            return Math.floorMod(prefix,bucketCount);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static <R> List<BucketResult<R>> execute(Config config, Function<Integer,R> computation) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(computation);
        ExecutorService pool=Executors.newFixedThreadPool(config.maxWorkers());
        var futures=new ArrayList<Future<BucketResult<R>>>();
        try {
            for (int bucket=0;bucket<config.bucketCount();bucket++) {
                final int index=bucket;
                futures.add(pool.submit(()->new BucketResult<>(index,computation.apply(index))));
            }
            var results=new ArrayList<BucketResult<R>>();
            for (var future:futures) results.add(future.get());
            return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f->f.cancel(true));
            throw new IllegalStateException("Interrupted batch; no result published",e);
        } catch (ExecutionException e) {
            futures.forEach(f->f.cancel(true));
            throw new IllegalStateException("Failed bucket; no result published",e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
