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
    public long acc;
    public int[] items;
    public long[] utils;

    public Transaction(long acc, int[] items, long[] utils) {
        this.acc = acc;
        this.items = items;
        this.utils = utils;
    }

    /** Sum of all utilities still present in this record (re(X,T) when X = the record's own prefix). */
    public long totalRemaining() {
        long s = 0;
        for (long u : utils) s += u;
        return s;
    }

    public int indexOf(int itemId) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == itemId) return i;
        }
        return -1;
    }
}
