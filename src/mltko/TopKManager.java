package mltko;

import java.util.*;

/**
 * Min-heap ({@link PriorityQueue}) of bounded size k, ordered by utility
 * ascending so that the smallest (weakest) pattern currently in the
 * Top-K sits at the head and can be evicted in O(log k) whenever a
 * stronger candidate arrives.
 *
 * Also implements the RUC (Raising Threshold strategy C) rule from
 * Tseng et al., "Efficient Algorithms for Mining Top-K High Utility
 * Itemsets": {@code mu = (heap not yet full) ? externalMu : utility of the head of the heap}.
 */
public final class TopKManager {

    // Maximum number of patterns to retain.
    private final int k;
    // Min-heap ordered ascending by utility, so the weakest Top-K entry sits at the head.
    private final PriorityQueue<Pattern> heap;
    // De-dup: keep only the best-known utility recorded per distinct itemset
    private final Map<List<Integer>, Long> seen = new HashMap<>();

    /**
     * Creates an empty Top-K manager.
     *
     * @param k the maximum number of patterns to retain
     */
    public TopKManager(int k) {
        this.k = k;
        this.heap = new PriorityQueue<>(Math.max(1, k), Comparator.comparingLong(p -> p.utility));
    }

    /**
     * Builds the de-duplication key for a pattern: its item ids as a
     * sorted, boxed list.
     *
     * @param items the pattern's item ids
     * @return a sorted {@code List<Integer>} suitable as a map key
     */
    private List<Integer> key(int[] items) {
        List<Integer> l = new ArrayList<>();
        for (int i : items) l.add(i);
        Collections.sort(l);
        return l;
    }

    /**
     * Attempts to insert a candidate pattern into the Top-K heap.
     *
     * Per RUC (spec B.4): {@code mu} is raised ONLY once
     * {@code |kList| == k}, to the min utility now in {@code kList};
     * before that, {@code mu} stays at whatever the caller already had
     * (typically the initial-mu from Sec. 4.1 of the mlTKO paper) - it
     * must NEVER drop back down just because the heap isn't full yet.
     * {@code externalMu} is that caller-held value, returned unchanged
     * whenever the heap still has room.
     *
     * @param p          the candidate pattern to offer
     * @param externalMu the caller's currently held minimum-utility threshold
     * @return the (possibly raised) minimum-utility threshold to use from now on; see {@link #currentMu}
     */
    public synchronized long offer(Pattern p, long externalMu) {
        List<Integer> kkey = key(p.items);
        Long prev = seen.get(kkey);
        if (prev != null && prev >= p.utility) {
            return currentMu(externalMu);
        }
        if (prev != null) {
            // remove stale entry with lower utility
            heap.removeIf(x -> key(x.items).equals(kkey));
        }
        seen.put(kkey, p.utility);
        heap.offer(p);
        while (heap.size() > k) {
            Pattern removed = heap.poll();
            seen.remove(key(removed.items));
        }
        return currentMu(externalMu);
    }

    /**
     * Computes the minimum-utility threshold implied by the heap's
     * current contents, without modifying it.
     *
     * @param externalMu the caller's currently held minimum-utility threshold
     * @return {@code Long.MAX_VALUE} if {@code k <= 0}; otherwise
     *         {@code externalMu} while the heap has fewer than k
     *         patterns, or {@code max(externalMu, heap head's utility)}
     *         once it is full
     */
    public long currentMu(long externalMu) {
        if (k <= 0) return Long.MAX_VALUE;
        if (heap.size() < k) return externalMu; // not yet full: keep caller's mu, never drop to 0
        return Math.max(externalMu, heap.peek().utility);
    }

    /**
     * Snapshots the heap's current contents in ranked order.
     *
     * @return the current contents of the heap as a list, sorted by
     *         descending utility (rank 1 first)
     */
    public List<Pattern> getSortedDescending() {
        List<Pattern> list = new ArrayList<>(heap);
        list.sort((a, b) -> Long.compare(b.utility, a.utility));
        return list;
    }

    /**
     * Reports how many patterns the heap currently holds.
     *
     * @return the number of patterns currently held in the heap (at most k)
     */
    public int size() {
        return heap.size();
    }
}
