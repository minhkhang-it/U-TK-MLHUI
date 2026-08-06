package mltko;

import java.util.HashMap;
import java.util.Map;

/**
 * Estimated Utility Co-occurrence Structure.
 * EUCS[x,y] = sum of TU(Tq) over every transaction Tq that contains both x and y.
 *
 * Memory optimisation: instead of Map<Pair<Integer,Integer>, Long> we pack
 * two 32-bit item ids into a single 64-bit long key:
 *   key = ((long) min(a,b) << 32) | (max(a,b) & 0xFFFFFFFFL)
 */
public final class EUCSMatrix {

    private final Map<Long, Long> map = new HashMap<>();

    public static long pack(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    public void add(int a, int b, long tu) {
        long key = pack(a, b);
        map.merge(key, tu, Long::sum);
    }

    public long get(int a, int b) {
        Long v = map.get(pack(a, b));
        return v == null ? 0L : v;
    }

    public int size() {
        return map.size();
    }
}
