package mltko;

import java.util.HashMap;
import java.util.Map;

/**
 * Estimated Utility Co-occurrence Structure (EUCS), as introduced by FHM
 * (Fournier-Viger et al.).
 *
 * {@code EUCS[x,y]} = sum of {@code TU(Tq)} over every transaction
 * {@code Tq} that contains both {@code x} and {@code y}. Used by
 * {@link MlTKOAlgorithm#search} to prune item pairs whose combined
 * transaction weight can never reach the current utility threshold.
 *
 * Memory optimisation: instead of {@code Map<Pair<Integer, Integer>, Long>},
 * two 32-bit item ids are packed into a single 64-bit {@code long} key
 * via {@link #pack(int, int)}.
 */
public final class EUCSMatrix {

    /** Co-occurrence totals, keyed by the packed pair id produced by {@link #pack(int, int)}. */
    private final Map<Long, Long> map = new HashMap<>();

    /** Creates an empty EUCS matrix. */
    public EUCSMatrix() {
    }

    /**
     * Packs an unordered pair of item ids into a single, order-independent
     * {@code long} key: {@code key = (min(a,b) << 32) | (max(a,b) & 0xFFFFFFFF)}.
     *
     * @param a the first item id
     * @param b the second item id
     * @return a key that is identical for {@code pack(a, b)} and {@code pack(b, a)}
     */
    public static long pack(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /**
     * Accumulates one transaction's utility into the co-occurrence entry
     * for a pair of items.
     *
     * @param a  the first item id
     * @param b  the second item id
     * @param tu the transaction utility TU(Tq) to add to {@code EUCS[a,b]}
     */
    public void add(int a, int b, long tu) {
        long key = pack(a, b);
        map.merge(key, tu, Long::sum);
    }

    /**
     * Looks up the accumulated co-occurrence value for a pair of items.
     *
     * @param a the first item id
     * @param b the second item id
     * @return the accumulated EUCS value for the pair, i.e. the sum of TU
     *         over every transaction containing both items, or {@code 0}
     *         if the pair never co-occurred
     */
    public long get(int a, int b) {
        Long v = map.get(pack(a, b));
        return v == null ? 0L : v;
    }

    /**
     * Reports how many distinct item pairs have been recorded so far.
     *
     * @return the number of distinct item pairs currently recorded
     */
    public int size() {
        return map.size();
    }
}
