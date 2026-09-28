package mltko;

import java.util.ArrayList;
import java.util.List;

/**
 * Records how the border minimum-utility threshold {@code mu} evolves
 * during a run:
 *   - the initial {@code mu} computed from the {@code {TWU(i):u(i)}}
 *     pairs of leaf items I (mlTKO paper, Sec. 4.1);
 *   - every subsequent raise performed by the RUC strategy (Tseng et al.,
 *     "Efficient Algorithms for Mining Top-K High Utility Itemsets",
 *     Sec. 4.2) while the DFS search (Algorithm 2 / FHM's Search
 *     procedure) is running.
 *
 * This log is purely diagnostic (it does not affect the algorithm's
 * correctness); it exists so the {@code mu} trajectory can be inspected
 * or exported for analysis (see {@code output_mu_threshold_log_{variant}.txt}).
 */
public final class MuThresholdLog {

    /** One recorded event in the {@code mu} trajectory: either the initial value, or one RUC raise. */
    public static final class Entry {
        /** Value of {@link MlTKOAlgorithm#candidatesGenerated} at the time of this event. */
        public final long candidateIndex;
        /** The threshold's value immediately before this event ({@code 0} for the initial entry). */
        public final long oldMu;
        /** The threshold's value immediately after this event. */
        public final long newMu;
        /** Human-readable cause of this event, e.g. {@code "initial (Sec. 4.1)"} or {@code "RUC raise"}. */
        public final String reason;
        /** The pattern that triggered a RUC raise, or {@code null} for the initial-mu entry. */
        public final int[] triggeringPattern;

        // Package-private: entries are only ever constructed by the enclosing MuThresholdLog.
        Entry(long candidateIndex, long oldMu, long newMu, String reason, int[] triggeringPattern) {
            this.candidateIndex = candidateIndex;
            this.oldMu = oldMu;
            this.newMu = newMu;
            this.reason = reason;
            this.triggeringPattern = triggeringPattern;
        }
    }

    // Chronological event history: initial mu, then every genuine RUC raise.
    private final List<Entry> entries = new ArrayList<>();

    /** Creates an empty {@code mu} trajectory log. */
    public MuThresholdLog() {
    }

    /**
     * Records the initial {@code mu} value computed before the DFS search
     * begins (mlTKO paper, Sec. 4.1, from leaf items I only).
     *
     * @param mu the initial minimum-utility threshold
     */
    public void recordInitial(long mu) {
        entries.add(new Entry(0, 0L, mu, "initial mu (Section 4.1, from leaf items I only)", null));
    }

    /**
     * Records a RUC threshold raise, if it is a genuine increase.
     *
     * @param candidateIndex     the value of the candidate counter at the time of the raise
     * @param oldMu              the threshold's value immediately before the raise
     * @param newMu              the threshold's value immediately after the raise
     * @param triggeringPattern  the item ids of the pattern that caused the raise
     */
    public void recordRaise(long candidateIndex, long oldMu, long newMu, int[] triggeringPattern) {
        if (newMu <= oldMu) return; // only log genuine raises
        entries.add(new Entry(candidateIndex, oldMu, newMu, "RUC raise", triggeringPattern));
    }

    /**
     * Returns the full recorded {@code mu} trajectory.
     *
     * @return all recorded events, in chronological order (initial entry first, if present)
     */
    public List<Entry> entries() {
        return entries;
    }
}
