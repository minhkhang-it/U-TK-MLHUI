package mltko;

import java.util.Arrays;

/**
 * A candidate pattern (itemset) discovered during the DFS search in
 * {@link MlTKOAlgorithm#search}.
 *
 * {@link #items} is always kept sorted ascending by item id, so that
 * two patterns representing the same itemset are considered equal /
 * hashable, which is needed to de-duplicate entries inside the Top-K
 * min-heap ({@link TopKManager}).
 */
public final class Pattern {

    /** Sorted item ids making up this pattern (leaf items and/or generalized category ids). */
    public final int[] items;
    /** This pattern's utility (the Utility-List's {@code sumIutil}) at the moment it was recorded. */
    public final long utility;

    /**
     * Creates a pattern from an item-id array and its utility.
     *
     * @param items   the pattern's item ids, in any order; a sorted copy is stored internally
     * @param utility the pattern's utility at the time of construction
     */
    public Pattern(int[] items, long utility) {
        int[] copy = items.clone();
        Arrays.sort(copy);
        this.items = copy;
        this.utility = utility;
    }

    /**
     * Two patterns are equal if and only if they contain the exact same
     * set of item ids (utility is not part of the identity).
     *
     * @param o the object to compare against
     * @return {@code true} if {@code o} is a {@code Pattern} with the same sorted {@link #items}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pattern)) return false;
        return Arrays.equals(items, ((Pattern) o).items);
    }

    /**
     * @return a hash code consistent with {@link #equals}, derived solely from {@link #items}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(items);
    }

    /**
     * Renders this pattern in compact {@code "id:name"} form
     * (comma-separated for multi-item patterns), as used by {@code
     * output_topk_patterns_{variant}.txt}.
     *
     * @param itemNames map from item id to display name; ids missing from
     *                  this map are rendered as {@code "Category-<id>"}
     * @return the compact id:name representation, without surrounding braces
     */
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

    /**
     * Renders this pattern in a verbose, brace-delimited form suitable
     * for human-readable logs, e.g. {@code "{110 (Fruits), 500 (AllGrocery)}"}.
     *
     * @param itemNames map from item id to display name; ids missing from
     *                  this map are rendered as {@code "Category-<id>"}
     * @return the verbose display representation, including surrounding braces
     */
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
