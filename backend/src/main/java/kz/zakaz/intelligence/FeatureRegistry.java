package kz.zakaz.intelligence;

import java.util.*;

/**
 * Frozen model-specific list of encoded feature slots. Dictionary IDs are opaque categories;
 * every model has its own versioned space and may share named feature groups.
 * Zero is a valid explicit numeric value: unknown must have its own presence feature.
 */
public record FeatureRegistry(String task, String version, List<String> orderedNames) {
    public FeatureRegistry {
        if (task == null || task.isBlank() || version == null || version.isBlank())
            throw new IllegalArgumentException("Task and version required");
        Objects.requireNonNull(orderedNames);
        orderedNames = List.copyOf(orderedNames);
        if (orderedNames.isEmpty() || orderedNames.stream().anyMatch(n -> n == null || !n.matches("[A-Za-z0-9_.-]+")))
            throw new IllegalArgumentException("Invalid feature names");
        if (new HashSet<>(orderedNames).size() != orderedNames.size())
            throw new IllegalArgumentException("Duplicate feature name");
    }

    public int position(String name) {
        int index = orderedNames.indexOf(name);
        if (index == -1) throw new IllegalArgumentException("Feature outside frozen registry: " + name);
        return index;
    }

    public boolean contains(String name) { return orderedNames.contains(name); }
}
