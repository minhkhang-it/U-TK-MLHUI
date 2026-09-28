package utkmlhui;

/**
 * A projected transaction record used during the Search() recursion.
 *
 * U-TK-MLHUI extension: every value here is already an EXPECTED utility
 * EU(i,T) = u(i,T) x p(i,T) (probability discounted once, at load time --
 * see Main.loadUtilityAndProbability()). From this point on the recursion
 * treats EU exactly like the original u, since EU is additive across items
 * / transactions the same way u is (Muc 1.3-1.4 cua dac ta).
 *
 * acc   = EU(X, T)  -> expected utility already accumulated by the current
 *                      prefix X inside this (possibly merged) transaction
 *                      group
 * items = the remaining extension items (already restricted to Sec(X)),
 *         kept sorted by the global processing order
 * utils = EU values parallel to items (EU(i,T) for each remaining item i)
 *
 * When several original transactions project to an identical `items`
 * sequence they are merged (Strategy 4 / merge) by summing `acc` and
 * `utils` element-wise into a single Transaction instance.
 */
public class Transaction {
    /** EU(X, T): expected utility already accumulated by the current search prefix X inside this (possibly merged) transaction group. */
    public double acc;
    /** Remaining extension items (already restricted to Sec(X)), kept sorted by the global processing order. */
    public int[] items;
    /** Expected utilities (EU) parallel to {@link #items}: {@code utils[i]} is EU(i,T) for {@code items[i]}. */
    public double[] utils;

    /**
     * Creates a (possibly already-merged) transaction record.
     *
     * @param acc   expected utility already accumulated by the current prefix, {@code EU(X, T)}
     * @param items the remaining extension item ids, sorted by the global processing order
     * @param utils EU values parallel to {@code items}
     */
    public Transaction(double acc, int[] items, double[] utils) {
        this.acc = acc;
        this.items = items;
        this.utils = utils;
    }

    /**
     * Sum of all EU still present in this record ({@code Ere(X,T)} when X = the record's own prefix).
     *
     * @return the sum of every value in {@link #utils}
     */
    public double totalRemaining() {
        double s = 0;
        for (double u : utils) s += u;
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
