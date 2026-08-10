package mltko;

import java.util.ArrayList;
import java.util.List;

/**
 * Records how the border minimum-utility threshold mu evolves during a run:
 *   - the initial mu computed from the {TWU(i):u(i)} pairs of leaf items I
 *     (mlTKO paper, Sec. 4.1)
 *   - every subsequent raise performed by the RUC strategy (Tseng et al.,
 *     "Efficient Algorithms for Mining Top-K High Utility Itemsets", Sec. 4.2)
 *     while the DFS search (Algorithm 2 / FHM's Search procedure) is running.
 *
 * This is purely diagnostic (does not affect the algorithm's correctness);
 * it exists so the mu trajectory can be inspected/exported for analysis.
 */
public final class MuThresholdLog {

    public static final class Entry {
        public final long candidateIndex; // candidatesGenerated counter at the time of the raise
        public final long oldMu;
        public final long newMu;
        public final String reason;     // e.g. "initial (Sec. 4.1)" or the pattern that triggered RUC
        public final int[] triggeringPattern; // null for the initial-mu entry

        Entry(long candidateIndex, long oldMu, long newMu, String reason, int[] triggeringPattern) {
            this.candidateIndex = candidateIndex;
            this.oldMu = oldMu;
            this.newMu = newMu;
            this.reason = reason;
            this.triggeringPattern = triggeringPattern;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void recordInitial(long mu) {
        entries.add(new Entry(0, 0L, mu, "initial mu (Section 4.1, from leaf items I only)", null));
    }

    public void recordRaise(long candidateIndex, long oldMu, long newMu, int[] triggeringPattern) {
        if (newMu <= oldMu) return; // only log genuine raises
        entries.add(new Entry(candidateIndex, oldMu, newMu, "RUC raise", triggeringPattern));
    }

    public List<Entry> entries() {
        return entries;
    }
}
