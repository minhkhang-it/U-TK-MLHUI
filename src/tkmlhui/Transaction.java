package tkmlhui;

/**
 * A projected transaction record used during the Search() recursion.
 *
 * acc   = u(X, T)  -> utility already accumulated by the current prefix X
 *                     inside this (possibly merged) transaction group
 * items = the remaining extension items (already restricted to Sec(X)),
 *         kept sorted by the global processing order
 * utils = utilities parallel to items
 *
 * When several original transactions project to an identical `items`
 * sequence they are merged (Strategy 4) by summing `acc` and `utils`
 * element-wise into a single Transaction instance.
 */
public class Transaction {
    /** u(X, T): utility already accumulated by the current search prefix X inside this (possibly merged) transaction group. */
    public long acc;
    /** Remaining extension items (already restricted to Sec(X)), kept sorted by the global processing order. */
    public int[] items;
    /** Utilities parallel to {@link #items}: {@code utils[i]} is the utility of {@code items[i]} in this record. */
    public long[] utils;

    /**
     * Creates a (possibly already-merged) transaction record.
     *
     * @param acc   utility already accumulated by the current prefix, {@code u(X, T)}
     * @param items the remaining extension item ids, sorted by the global processing order
     * @param utils utilities parallel to {@code items}
     */
    public Transaction(long acc, int[] items, long[] utils) {
        this.acc = acc;
        this.items = items;
        this.utils = utils;
    }

    /**
     * Sum of all utilities still present in this record ({@code re(X,T)} when X = the record's own prefix).
     *
     * @return the sum of every value in {@link #utils}
     */
    public long totalRemaining() {
        long s = 0;
        for (long u : utils) s += u;
        return s;
    }

    /**
     * Finds the position of an item id within {@link #items}.
     *
     * @param itemId the item id to look for
     * @return the index of {@code itemId} in {@link #items}, or {@code -1} if it is not present
     */
    public int indexOf(int itemId) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == itemId) return i;
        }
        return -1;
    }
}
