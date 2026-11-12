package kz.zakaz.intelligence;

import java.util.*;

/**
 * Deterministic named/sparse feature projection for later Tribuo Feature(name, value) creation.
 * Does not depend on Tribuo runtime or accept arbitrary new category dimensions.
 */
public final class FeatureVectorEncoder {
    private FeatureVectorEncoder() {}

    public record NamedFeature(String name, double value) {}

    public record Encoded(String task, String schemaVersion, List<NamedFeature> features, double[] dense) {
        public Encoded {
            features = List.copyOf(features);
            dense = dense.clone();
        }
        @Override public double[] dense() { return dense.clone(); }
    }

    public static Encoded encode(FeatureRegistry registry, Map<String, Double> values) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(values);
        if (!registry.orderedNames().containsAll(values.keySet()))
            throw new IllegalArgumentException("Unregistered feature");
        var named = new ArrayList<NamedFeature>();
        var dense = new double[registry.orderedNames().size()];
        for (int i=0; i<registry.orderedNames().size(); i++) {
            String name = registry.orderedNames().get(i);
            double value = values.getOrDefault(name, 0.0d);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("NaN or infinite feature " + name);
            dense[i] = value;
            if (value != 0.0d) named.add(new NamedFeature(name, value));
        }
        return new Encoded(registry.task(), registry.version(), named, dense);
    }

    /** Compare two opaque dictionary codes by equality and emit a binary MATCH feature. */
    public static double exactMatch(String expected, String actual) {
        return expected != null && actual != null && expected.equals(actual) ? 1.0 : 0.0;
    }
}
