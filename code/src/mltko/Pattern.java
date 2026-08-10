package mltko;

import java.util.Arrays;

/**
 * A candidate pattern (itemset) discovered during the DFS search.
 * `items` always kept sorted ascending by item id so that two patterns
 * with the same item-set are considered equal / hashable (needed to
 * de-duplicate entries inside the Top-K min-heap).
 */
public final class Pattern {

    public final int[] items;      // sorted item ids (leaf items and/or generalized category ids)
    public final long utility;     // sumIutil of the pattern's utility-list at the moment it was recorded

    public Pattern(int[] items, long utility) {
        int[] copy = items.clone();
        Arrays.sort(copy);
        this.items = copy;
        this.utility = utility;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pattern)) return false;
        return Arrays.equals(items, ((Pattern) o).items);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(items);
    }

    /** Compact "id:name" form (comma-separated for multi-item patterns), used by output_topk_patterns.txt. */
    public String toIdNameString(java.util.Map<Integer, String> itemNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            int id = items[i];
            String name = itemNames.get(id);
            if (name == null) name = "Category-" + id;
            sb.append(id).append(":").append(name);
            if (i < items.length - 1) sb.append(", ");
        }
        return sb.toString();
    }

    public String toDisplayString(java.util.Map<Integer, String> itemNames) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.length; i++) {
            int id = items[i];
            String name = itemNames.get(id);
            if (name == null) name = "Category-" + id;
            sb.append(id).append(" (").append(name).append(")");
            if (i < items.length - 1) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }
}
