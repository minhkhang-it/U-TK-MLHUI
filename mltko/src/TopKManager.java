package mltko;

import java.util.*;

/**
 * Min-Heap (PriorityQueue) of bounded size k, ordered by utility ascending
 * so that the smallest (weakest) pattern currently in the Top-K sits at the
 * head and can be evicted in O(log k) whenever a stronger candidate arrives.
 *
 * Also implements the RUC (Raising Threshold strategy C) rule:
 *   mu = (heap not yet full) ? 0 : utility of the head of the heap
 */
public final class TopKManager {

    private final int k;
    private final PriorityQueue<Pattern> heap;
    // De-dup: keep only the best-known utility recorded per distinct itemset
    private final Map<List<Integer>, Long> seen = new HashMap<>();

    public TopKManager(int k) {
        this.k = k;
        this.heap = new PriorityQueue<>(Math.max(1, k), Comparator.comparingLong(p -> p.utility));
    }

    private List<Integer> key(int[] items) {
        List<Integer> l = new ArrayList<>();
        for (int i : items) l.add(i);
        Collections.sort(l);
        return l;
    }

    /**
     * Attempt to insert pattern p. Per RUC (spec B.4): mu is raised ONLY once
     * |kList| == k, to the min utility now in kList; before that, mu must
     * stay at whatever the caller already had (typically the initial-mu from
     * Sec 4.1) -- it must NEVER drop back down just because the heap isn't
     * full yet. `externalMu` is that caller-held value, returned unchanged
     * whenever the heap still has room.
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

    public long currentMu(long externalMu) {
        if (k <= 0) return Long.MAX_VALUE;
        if (heap.size() < k) return externalMu; // not yet full: keep caller's mu, never drop to 0
        return Math.max(externalMu, heap.peek().utility);
    }

    public List<Pattern> getSortedDescending() {
        List<Pattern> list = new ArrayList<>(heap);
        list.sort((a, b) -> Long.compare(b.utility, a.utility));
        return list;
    }

    public int size() {
        return heap.size();
    }
}
