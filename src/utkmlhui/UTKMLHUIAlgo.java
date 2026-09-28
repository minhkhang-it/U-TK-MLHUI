package utkmlhui;

import java.util.*;

/**
 * U-TK-MLHUI (Uncertain Top-k Multi-Level High Utility Itemset Mining),
 * re-implemented to follow precisely "TAI LIEU DAC TA KY THUAT - Thang 2"
 * (the U-TK-MLHUI spec), which extends:
 *   - Nguyen, Tung & Vo, "Mining top-k multi-level high utility itemsets"
 *     (Knowledge-Based Systems, Vol. 316, 2025) - TK-MLHUI itself, whose
 *     exact search architecture (horizontal-database projection-and-merge,
 *     Local/Sub-tree Utility pruning, Strategies 1-4) this class reuses
 *     unchanged, with every quantity computed on EU instead of u (see below).
 *
 * Every leaf item occurrence in every transaction carries an existential
 * probability p(i,T) in (0,1] (Dinh nghia 3.2). This is implemented by
 * converting each raw leaf utility u(i,T) into its EXPECTED utility
 *
 *     EU(i,T) = u(i,T) x p(i,T)                      (Dinh nghia 3.3)
 *
 * ONCE, at load time (see Main.loadUtilityAndProbability()). The taxonomy
 * itself is a WEIGHTED, MULTI-PARENT DAG (Dinh nghia 3.1): a leaf/variant
 * may have several direct parents at once, each edge (c,g) carrying its own
 * confidence weight(c->g) in (0,1]. Every generalised item's expected
 * utility is therefore rolled up tier by tier, not by a single flat sum:
 *   - EU(g,T)   = sum [EU(c,T) x weight(c->g)], c in Children(g)  (Dinh nghia 3.4)
 *   - EU(X,T)   = sum EU(i,T),  i in X                             (Dinh nghia 3.5)
 *   - EU(X)     = sum EU(X,Tn), over Tn containing X                (Dinh nghia 3.6)
 * (see Taxonomy.computeTransactionEU for the tiered roll-up itself).
 * Bo de A guarantees EU(i,T) >= 0 for every id at every tier (proved by
 * induction over the DAG's tiers, base case = leaf), which is exactly the
 * non-negativity TK-MLHUI's Property 4-8 already rely on for u -- so the
 * whole DCP proof structure (Property 4'-8', Chuong 4) carries over
 * unchanged with u replaced by EU, lu replaced by Elu, su replaced by Esu
 * and TWU replaced by EGTWU, REGARDLESS of the taxonomy being a multi-parent
 * DAG instead of a single-parent tree (the proofs never depend on how
 * EU(g,T) is computed internally). In other words: this class is
 * TK-MLHUI's exact search algorithm, run on EU instead of u.
 *
 * Implements:
 *   - Elu (Expected Local Utility) and Esu (Expected Sub-tree Utility)
 *     upper bounds (Muc 3.2-3.3)
 *   - Strategy 1&2: high initial minU from single items + merged
 *     transactions, computed from EU instead of u (Muc 4.1-4.2)
 *   - Strategy 3 : dynamic threshold raising via a size-k min-heap,
 *     ranking by EU (Muc 4.3)
 *   - Strategy 4 : database projection & transaction merging at every step
 *   - Level-based threshold raising, from maxLevel down to level 0 (Muc 4.4)
 *
 * The database is processed one taxonomy level at a time, from the
 * deepest (most specialised) level down to level 0 (most general),
 * while minU is carried over (and only ever increases) across levels.
 *
 * This class' {@link Variant} switch directly corresponds to the spec's
 * five-baseline ablation study (Muc 5.1): see {@link Variant} for how each
 * baseline isolates exactly one strategy. See {@link Main} for how all
 * five variants are run together in one ablation study.
 */
public class UTKMLHUIAlgo {

    /**
     * The FIVE ablation baselines requested in the spec (Muc 5.1), each
     * isolating exactly ONE strategy so its individual contribution can be
     * measured against Baseline A (full):
     *
     *   - useElu   (Elu pruning, Sec(X) via Elu(X,w) >= minU, Muc 3.2/3.5)
     *   - useEsu   (Esu pruning, Pri(X) via Esu(X,w) >= minU, Muc 3.3/3.5)
     *   - mergeBeforeMining (merge identical generalised transactions
     *     before a level's search begins, i.e. buildLevelDatabase's merge)
     *   - initialThresholdBoost  (Strategy 1&2, Muc 4.1-4.2: initial minU
     *     from single-item EU + merged-transaction EU)
     *   - dynamicPromisingUpdate (Strategy 3, Muc 4.3: raising minU while
     *     walking the top-k heap during Search())
     *
     * Baseline B (wo-Elu) and C (wo-Esu) each turn off ONLY their own
     * pruning step; the other one stays on so the branch is still explored
     * (just less tightly pruned) rather than the search architecture
     * breaking. Baseline E (wo-threshold-raising) turns off BOTH Strategy 1&2
     * and Strategy 3 together, per the spec text ("khong nang minU ... giu
     * minU = 0 khi bat dau") -- i.e. minU never leaves 0 for the whole run.
     */
    public enum Variant {
        FULL("U-TK-MLHUI", true, true, true, true, true),
        WO_ELU("U-TK-MLHUI-wo-Elu", false, true, true, true, true),
        WO_ESU("U-TK-MLHUI-wo-Esu", true, false, true, true, true),
        WO_MERGE("U-TK-MLHUI-wo-merge", true, true, false, true, true),
        WO_THRESHOLD_RAISING("U-TK-MLHUI-wo-threshold-raising", true, true, true, false, false);

        /** Display/file-suffix label for this variant, e.g. {@code "U-TK-MLHUI-wo-Elu"}. */
        public final String label;
        /** Whether Elu pruning (Sec(X) via {@code Elu(X,w) >= minU}) is enabled. */
        public final boolean useElu;
        /** Whether Esu pruning (Pri(X) via {@code Esu(X,w) >= minU}) is enabled. */
        public final boolean useEsu;
        /** Whether Strategy 4's pre-mining merge (consolidating identical generalised transactions per level) is enabled. */
        public final boolean mergeBeforeMining;
        /** Whether Strategy 1&2 (high initial minU from single-item + merged-transaction EU) is enabled. */
        public final boolean initialThresholdBoost;
        /** Whether Strategy 3 (raising minU from the top-k heap while walking {@link #search}) is enabled. */
        public final boolean dynamicPromisingUpdate;

        /**
         * Defines one ablation baseline by naming which strategies stay switched on.
         *
         * @param label                  display/file-suffix label for this variant
         * @param useElu                 whether to enable Elu pruning
         * @param useEsu                 whether to enable Esu pruning
         * @param mergeBeforeMining      whether to enable Strategy 4's pre-mining transaction merge
         * @param initialThresholdBoost  whether to enable Strategy 1&2 (initial minU boost)
         * @param dynamicPromisingUpdate whether to enable Strategy 3 (dynamic threshold raising)
         */
        Variant(String label, boolean useElu, boolean useEsu, boolean mergeBeforeMining,
                boolean initialThresholdBoost, boolean dynamicPromisingUpdate) {
            this.label = label;
            this.useElu = useElu;
            this.useEsu = useEsu;
            this.mergeBeforeMining = mergeBeforeMining;
            this.initialThresholdBoost = initialThresholdBoost;
            this.dynamicPromisingUpdate = dynamicPromisingUpdate;
        }

        /**
         * Resolves a command-line argument into a {@link Variant}, matched
         * case-insensitively with {@code "-"}/{@code "_"} interchangeable.
         *
         * @param s the raw CLI token to match (e.g. {@code "wo-elu"}, {@code "WO_ESU"})
         * @return the matching {@link Variant}, or {@code null} if {@code s} names none of them
         */
        public static Variant fromArg(String s) {
            String n = s.trim().toLowerCase().replace("_", "-");
            switch (n) {
                case "full":
                case "u-tk-mlhui":
                    return FULL;
                case "wo-elu":
                case "woelu":
                    return WO_ELU;
                case "wo-esu":
                case "woesu":
                    return WO_ESU;
                case "wo-merge":
                case "womerge":
                    return WO_MERGE;
                case "wo-threshold-raising":
                case "wo-tr":
                case "wothresholdraising":
                case "wotr":
                    return WO_THRESHOLD_RAISING;
                default:
                    return null;
            }
        }
    }

    /** Which ablation variant this instance runs; set by the caller before {@link #run()}. Defaults to {@link Variant#FULL}. */
    public Variant variant = Variant.FULL;

    // ---- input model -------------------------------------------------
    /** id -> {@link Item} (leaf or generalised), populated by {@link #registerItem}. */
    private final Map<Integer, Item> items = new HashMap<>();
    /** The dataset's taxonomy (is-a hierarchy), supplied at construction time. */
    private final Taxonomy taxonomy;
    /** id -> multi-level height, computed once in {@link #run()} via {@link Taxonomy#computeHeights}. */
    private Map<Integer, Integer> height;
    /** The highest height among all registered items; {@link #run()} processes levels {@code maxLevel} down to 0. */
    private int maxLevel;

    /** Raw leaf-level item ids of every transaction, as registered via {@link #addTransaction}. */
    private List<int[]> rawItemsOfTx = new ArrayList<>();
    /** Raw leaf-level EU(i,T) values parallel to {@link #rawItemsOfTx}, already probability-discounted at load time. */
    private List<double[]> rawEUOfTx = new ArrayList<>();

    /** The number of top patterns to mine, set via {@link #setK}. */
    private int k;

    // ---- mining state --------------------------------------------------
    /** The current minimum-utility threshold (over EU); only ever increases during {@link #run()}. */
    private double minU = 0;
    /** item id -> global processing rank (0 = highest EGTWU), used to order items within a transaction. */
    private Map<Integer, Integer> rankOf;

    /** One candidate top-k pattern: an itemset plus its exact expected utility EU(X). */
    static class Pattern {
        /** The item ids making up this pattern. */
        int[] itemIds;
        /** EU(X): this pattern's exact expected utility. */
        double utility;
        /**
         * Creates a pattern record.
         *
         * @param itemIds the item ids making up this pattern
         * @param utility EU(X), this pattern's exact expected utility
         */
        Pattern(int[] itemIds, double utility) { this.itemIds = itemIds; this.utility = utility; }
    }

    /** Size-k min-heap of the best patterns found so far; its minimum is the current top-k boundary. */
    private final PriorityQueue<Pattern> heap =
            new PriorityQueue<>(Comparator.comparingDouble(p -> p.utility));

    /**
     * Tolerant replacement for {@code value >= minU}, used at every EU/Elu/Esu
     * vs minU comparison. minU and the value being compared against it are
     * each accumulated by summing many per-transaction doubles, but along
     * different traversal/merge orders (which differ further across ablation
     * variants, e.g. wo-Elu keeps extra items in transactions before they get
     * merged). Since floating-point addition is not associative, two sums
     * that are mathematically identical can land a few ULPs apart depending
     * on the order they were added in. Without this tolerance, a pattern
     * whose true EU exactly equals minU (a real, expected case whenever the
     * dataset legitimately ties for the k-th spot) could round a hair below
     * minU purely from summation order and get silently dropped -- yielding
     * fewer than k patterns even though a k-th one genuinely exists.
     *
     * @param value the EU/Elu/Esu value to test
     * @return whether {@code value} is at or above {@link #minU}, within tolerance
     */
    private boolean geMinU(double value) {
        return value >= minU - Math.max(1e-6, Math.abs(minU) * 1e-9);
    }

    // ---- statistics / logs ---------------------------------------------
    /** Total number of candidate itemsets (Search() recursive calls) visited during the last {@link #run()}. */
    public long candidateCount = 0;
    /** Total number of transaction records scanned (across all Elu/Esu computations, projections, and merges) during the last run. */
    public long scannedListsCount = 0;
    /** Wall-clock execution time of the last {@link #run()} call, in milliseconds. */
    public long executionTimeMs = 0;
    /** Peak JVM heap usage observed during the last {@link #run()} call, in megabytes. */
    public double peakMemoryMB = 0;
    /** Records the minU trajectory (initial value plus every Strategy-3 raise) for the last run. */
    public final List<String> muThresholdLog = new ArrayList<>();

    // ---- debug tracing ---------------------------------------------------
    /** Whether to record a full step-by-step trace into {@link #debugLog} during {@link #run()}. */
    public boolean debug = false;
    /** The step-by-step debug trace, populated only when {@link #debug} is {@code true}. */
    public final List<String> debugLog = new ArrayList<>();
    /** Maximum number of lines kept in {@link #debugLog}, to avoid runaway files on big datasets. */
    private static final int DEBUG_LINE_CAP = 20000;
    /** Whether the truncation marker has already been appended to {@link #debugLog}. */
    private boolean debugTruncated = false;

    /**
     * Appends one line to {@link #debugLog} when {@link #debug} is enabled, truncating at {@link #DEBUG_LINE_CAP}.
     *
     * @param msg the trace line to record
     */
    private void dbg(String msg) {
        if (!debug) return;
        if (debugLog.size() >= DEBUG_LINE_CAP) {
            if (!debugTruncated) {
                debugLog.add("... [debug log truncated at " + DEBUG_LINE_CAP + " lines] ...");
                debugTruncated = true;
            }
            return;
        }
        debugLog.add(msg);
    }

    /**
     * Creates a miner over the given taxonomy. Call {@link #registerItem},
     * {@link #addTransaction}, and {@link #setK} to load a dataset, then
     * call {@link #run()}.
     *
     * @param taxonomy the dataset's taxonomy (is-a hierarchy)
     */
    public UTKMLHUIAlgo(Taxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    // =====================================================================
    // INPUT REGISTRATION (called by the Main / loader)
    // =====================================================================

    /**
     * Registers an item id with an optional display name. A no-op if the
     * id was already registered (its existing name is kept).
     *
     * @param id   the item id (leaf or generalised taxonomy node)
     * @param name display name, or {@code null} if not yet known
     */
    public void registerItem(int id, String name) {
        items.computeIfAbsent(id, x -> new Item(id, name));
    }

    /**
     * Registers one transaction, already converted to EXPECTED utility.
     * euValues[idx] must already equal u(itemIds[idx], T) x p(itemIds[idx], T)
     * -- see Main.loadUtilityAndProbability() for where that multiplication
     * happens (and where p is validated to be in (0,1]).
     *
     * @param itemIds  the transaction's leaf item ids
     * @param euValues EU(i,T) values parallel to {@code itemIds}
     */
    public void addTransaction(int[] itemIds, double[] euValues) {
        rawItemsOfTx.add(itemIds);
        rawEUOfTx.add(euValues);
    }

    /**
     * Sets the number of top patterns to mine.
     *
     * @param k the top-k parameter
     */
    public void setK(int k) {
        this.k = k;
    }

    // =====================================================================
    // MAIN ENTRY POINT
    // =====================================================================

    /**
     * Runs the full U-TK-MLHUI mining pipeline: computes item heights and
     * EGTWU/expected-utility, establishes the global processing order,
     * computes the Strategy 1&2 initial minU (from EU), then mines each
     * taxonomy level from {@code maxLevel} down to 0 via
     * {@link #processLevel}, carrying minU forward across levels.
     * Populates every public metric field ({@link #executionTimeMs},
     * {@link #peakMemoryMB}, {@link #candidateCount},
     * {@link #scannedListsCount}, and {@link #muThresholdLog}) as a side
     * effect.
     *
     * Intended to be called exactly once per instance; construct a fresh
     * {@code UTKMLHUIAlgo} for a repeat run.
     *
     * @return the top-k patterns found, sorted by descending expected utility
     */
    public List<Pattern> run() {
        long t0 = System.currentTimeMillis();
        MemoryLogger.getInstance().reset();

        // 2: I = leaf items, GI = generalised items
        Set<Integer> leafIds = new HashSet<>();
        for (int[] tx : rawItemsOfTx) for (int id : tx) leafIds.add(id);

        // Register EVERY item that participates in the mining space (leaves from the
        // transactions, plus every node ever mentioned in taxonomy.txt) before computing
        // heights/maxLevel. Correctness must not depend on name.txt being complete --
        // a missing @ITEM= line should only affect display names, never the mining
        // itself. (Registering here is a no-op for ids already added by the loader.)
        for (int id : leafIds) registerItem(id, null);
        for (int node : taxonomy.allNodes) registerItem(node, null);

        height = taxonomy.computeHeights(leafIds);
        for (Item it : items.values()) {
            it.height = height.getOrDefault(it.id, 0);
            maxLevel = Math.max(maxLevel, it.height);
        }
        if (debug) {
            dbg("=== [STEP] Height computation (level 0 = leaf, increasing towards root) ===");
            dbg("leafIds count = " + leafIds.size() + " | total distinct items (leaf+GI) = " + items.size()
                    + " | maxLevel = " + maxLevel);
            List<Integer> byHeight = new ArrayList<>(items.keySet());
            byHeight.sort(Comparator.comparingInt((Integer id) -> items.get(id).height).thenComparingInt(id -> id));
            for (int id : byHeight) {
                Item it = items.get(id);
                dbg(String.format("  id=%d name=%s height=%d", id, it.name, it.height));
            }
        }

        // 3: EGTWU (multi-level, deduped per transaction) + EU (rolled up through ancestors)
        computeEgtwuAndExpectedUtility();

        // 4: global processing order, cung level thi EGTWU nho hon dung truoc (tang dan)
        List<Integer> order = new ArrayList<>(items.keySet());
        order.sort((a, b) -> {
            int la = items.get(a).height, lb = items.get(b).height;
            if (la != lb) return Integer.compare(lb, la);      // level cao hon truoc
            double ta = items.get(a).egtwu, tb = items.get(b).egtwu;
            if (ta != tb) return Double.compare(ta, tb);        // EGTWU tang dan
            return Integer.compare(a, b);
        });
        rankOf = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rankOf.put(order.get(i), i);

        if (debug) {
            dbg("\n=== [STEP] Global processing order (EGTWU desc, tie-break id asc) ===");
            for (int id : order) {
                Item it = items.get(id);
                dbg(String.format("  rank=%d id=%d name=%s height=%d EGTWU=%.6f EU=%.6f",
                        rankOf.get(id), id, it.name, it.height, it.egtwu, it.expectedUtility));
            }
        }

        // 7: Strategy 1 & 2 -> initial minU (from EU, Muc 4.1-4.2)
        minU = computeInitialMinU();
        muThresholdLog.add(String.format(
                "INIT | candidates=0 | minU: (none) -> %.6f | trigger=Strategy1&2 (top-k EU of single items + merged transactions)",
                minU));
        if (debug) {
            dbg("\n=== [STEP] Strategy 1&2 initial minU ===");
            dbg("  computed minU = " + minU);
        }

        // 8: for l = maxLevel downto 0
        for (int l = maxLevel; l >= 0; l--) {
            MemoryLogger.getInstance().checkMemory();
            processLevel(l);
        }

        MemoryLogger.getInstance().checkMemory();
        executionTimeMs = System.currentTimeMillis() - t0;
        peakMemoryMB = MemoryLogger.getInstance().getPeakMB();

        List<Pattern> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.utility, a.utility));
        return result;
    }

    // =====================================================================
    // EGTWU / EU roll-up
    // =====================================================================

    /**
     * One full pass over the raw transactions: accumulates each leaf item's
     * (and every ancestor generalised item's) EGTWU and expected utility
     * (EU), per Dinh nghia 3.3-3.4 (weighted, multi-parent DAG roll-up).
     * For each transaction, {@link Taxonomy#computeTransactionEU} first
     * computes EU(id,T) tier by tier (leaf -> variant -> lemma, weighted by
     * weight(c->g) at every step) for every id reachable from that one
     * transaction; this method then folds those per-transaction values into
     * the running global EU(id) and EGTWU(id) totals. EGTWU credit is
     * deduped per transaction (a repeated leaf/ancestor only contributes the
     * transaction's total EU once, exactly like the original TWU), while
     * expected utility is the true additive sum of {@code EU(id,T)} across
     * all transactions.
     */
    private void computeEgtwuAndExpectedUtility() {
        for (int t = 0; t < rawItemsOfTx.size(); t++) {
            int[] tx = rawItemsOfTx.get(t);
            double[] eu = rawEUOfTx.get(t);
            double total = 0; // ETU(Tn) = sum of EU over the whole (leaf-level) transaction
            for (double u : eu) total += u;

            Map<Integer, Double> euAtT = taxonomy.computeTransactionEU(tx, eu, height, maxLevel);
            for (Map.Entry<Integer, Double> e : euAtT.entrySet()) {
                int id = e.getKey();
                double euIdT = e.getValue();
                if (euIdT == 0.0) continue; // not actually present in this transaction
                registerItem(id, null);
                Item it = items.get(id);
                it.expectedUtility += euIdT; // EU(id) += EU(id,T)   [Dinh nghia 3.3-3.4]
                it.egtwu += total;           // EGTWU(id) += ETU(Tn), deduped by construction (euAtT has one entry per id)
            }
        }
    }

    // =====================================================================
    // Strategy 1 & 2: initial minU (from EU instead of u, Muc 4.1-4.2)
    // =====================================================================

    /**
     * Minimum itemset size accepted into the reported top-k (default: 2).
     * Application-specific constraint for the linguistic uncertain dataset:
     * a size-1 "pattern" is just a single leaf/variant/lemma translation on
     * its own, not a co-occurrence pattern, so it carries no meaning for
     * this use case and is excluded from the final result -- NOT merely
     * filtered out of the printed output after the fact (which would leave
     * minU mis-seeded, see {@link #computeInitialMinU}), but excluded at
     * the point candidates are offered to the top-k heap in {@link #search}.
     * Recursion below a size-1 prefix still happens exactly as before
     * (needed to reach size-2+ extensions) -- only the heap-eligibility
     * check changes.
     */
    public int minPatternSize = 2;

    /**
     * Computes Strategy 1&2's high initial minU (Muc 4.1-4.2): pools the
     * summed EU of every distinct (leaf-level, merged) raw transaction of
     * at least {@link #minPatternSize} items, sorts descending, and takes
     * the k-th largest value as an admissible lower bound -- or 0 if either
     * the variant disables this strategy or the pool has fewer than k
     * entries (in which case using pool[k-1] would risk over-pruning).
     *
     * Strategy 1 (EU of every single item) is only included when
     * {@link #minPatternSize} <= 1 -- with the default minPatternSize = 2,
     * single items are not eligible results at all, so their EU must not be
     * allowed to seed/raise minU (a single item's high EU is irrelevant
     * competition for a slot reserved for size-2+ itemsets, and including
     * it would risk pruning away genuinely qualifying multi-item patterns).
     *
     * @return the initial minU, or 0 if Strategy 1&2 is disabled or unsafe to apply
     */
    private double computeInitialMinU() {
        if (!variant.initialThresholdBoost) {
            if (debug) dbg("  Strategy 1&2 (initial threshold boost) disabled for variant " + variant.label + " -> minU starts at 0");
            return 0;
        }
        List<Double> pool = new ArrayList<>();
        // Strategy 1: EU of every single item (each id is its own distinct itemset) --
        // only a valid competitor pool when singletons are themselves eligible results.
        if (minPatternSize <= 1) {
            for (Item it : items.values()) pool.add(it.expectedUtility);
        }

        // Strategy 2: treat every RAW (leaf-level, ungeneralised) transaction of at least
        // minPatternSize items as a sample itemset, summing its EU. Transactions with an
        // identical leaf item set are merged (summed) first, so each pool entry here
        // corresponds to exactly one distinct real itemset. We deliberately do NOT repeat
        // this across every taxonomy level: since generalisation only regroups EU without
        // changing its sum, doing so would let the same underlying transaction inject
        // several pool entries at (near-)identical values for what can amount to far fewer
        // than k truly distinct itemsets, which would make minU unsafe (over-pruning).
        Map<String, Double> rawGroups = new LinkedHashMap<>();
        for (int t = 0; t < rawItemsOfTx.size(); t++) {
            int[] tx = rawItemsOfTx.get(t);
            if (tx.length < minPatternSize) continue; // this transaction alone can't form an eligible itemset
            double[] eu = rawEUOfTx.get(t);
            Integer[] sortedIdx = new Integer[tx.length];
            for (int i = 0; i < tx.length; i++) sortedIdx[i] = i;
            Arrays.sort(sortedIdx, (a, b) -> Integer.compare(tx[a], tx[b]));
            int[] sortedItems = new int[tx.length];
            double total = 0;
            for (int i = 0; i < tx.length; i++) {
                sortedItems[i] = tx[sortedIdx[i]];
                total += eu[sortedIdx[i]];
            }
            String key = Arrays.toString(sortedItems);
            rawGroups.merge(key, total, Double::sum);
        }
        pool.addAll(rawGroups.values());

        pool.sort(Collections.reverseOrder());
        // Safety condition: minU = pool.get(k-1) is only an admissible (non-over-pruning)
        // bound when the pool contains AT LEAST k distinct valid candidate patterns
        // (each pool entry is itself a real ELIGIBLE itemset, size >= minPatternSize). If
        // the pool has fewer than k entries we cannot yet guarantee k patterns reach that
        // value, so we must not raise minU above 0 here; Strategy 3 will raise it safely
        // once real search begins.
        if (debug) {
            dbg("  pool size = " + pool.size() + " (need >= k=" + k + " for a non-zero bound)");
            int show = Math.min(pool.size(), Math.max(k + 5, 20));
            dbg("  pool top " + show + " values (desc): " + pool.subList(0, show));
        }
        if (pool.size() < k) return 0;
        return pool.get(k - 1);
    }

    // =====================================================================
    // Per-level database construction (generalisation + merge)  [Strategy 4]
    // =====================================================================

    /**
     * Generalises every raw transaction to level l, dedupes within-transaction, sorts by rank, then merges.
     *
     * Uses the SAME tiered, weighted roll-up as {@link #computeEgtwuAndExpectedUtility}
     * ({@link Taxonomy#computeTransactionEU}), then keeps only the ids whose
     * height equals {@code l} -- with a multi-parent DAG a single leaf can
     * now generalise into SEVERAL distinct items at the same level at once
     * (each carrying its own weighted share of EU), unlike the old
     * single-parent {@code generalize()} which produced exactly one id per
     * leaf per level.
     *
     * @param l the taxonomy level (height) to generalise every transaction to
     * @return the level-{@code l} database D(l): one record per (merged, if enabled) generalised transaction
     */
    private List<Transaction> buildLevelDatabase(int l) {
        List<Transaction> raw = new ArrayList<>();
        for (int t = 0; t < rawItemsOfTx.size(); t++) {
            int[] tx = rawItemsOfTx.get(t);
            double[] eu = rawEUOfTx.get(t);

            Map<Integer, Double> euAtT = taxonomy.computeTransactionEU(tx, eu, height, maxLevel);
            Map<Integer, Double> merged = new HashMap<>();
            for (Map.Entry<Integer, Double> e : euAtT.entrySet()) {
                int id = e.getKey();
                double val = e.getValue();
                if (val == 0.0) continue;
                if (height.getOrDefault(id, 0) != l) continue; // keep only items actually AT this level
                merged.put(id, val);
            }
            if (merged.isEmpty()) continue;

            List<Integer> ids = new ArrayList<>(merged.keySet());
            ids.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));

            int[] itemsArr = new int[ids.size()];
            double[] eusArr = new double[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                itemsArr[i] = ids.get(i);
                eusArr[i] = merged.get(ids.get(i));
            }
            raw.add(new Transaction(0.0, itemsArr, eusArr));
        }
        // Strategy 4a: merge identical generalised transactions before the level's
        // search even begins. wo-merge skips this: it leaves D(l) as one record per
        // original transaction, so later steps (Elu/Esu, projections, scannedListsCount)
        // all have to walk a longer, non-deduplicated list. This is a pure performance
        // knob -- the summed Elu/Esu/EU values are identical either way, so results
        // (top-k patterns) are unaffected.
        return variant.mergeBeforeMining ? mergeTransactions(raw) : raw;
    }

    /**
     * Groups transactions with an identical item-id sequence, summing acc and utils element-wise.
     *
     * @param raw the transaction records to group
     * @return one merged {@link Transaction} per distinct item-id sequence found in {@code raw}
     */
    private List<Transaction> mergeTransactions(List<Transaction> raw) {
        Map<String, Transaction> grouped = new LinkedHashMap<>();
        for (Transaction tr : raw) {
            String key = Arrays.toString(tr.items);
            Transaction existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, new Transaction(tr.acc, tr.items.clone(), tr.utils.clone()));
            } else {
                existing.acc += tr.acc;
                for (int i = 0; i < existing.utils.length; i++) existing.utils[i] += tr.utils[i];
            }
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * Projects a database onto keepSet only (drops other items), preserving relative order.
     *
     * @param data    the transaction records to project
     * @param keepSet the item ids to keep; any item not in this set is dropped from every record
     * @return the projected (and re-merged) database
     */
    private List<Transaction> project(List<Transaction> data, Set<Integer> keepSet) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction tr : data) {
            int cnt = 0;
            for (int id : tr.items) if (keepSet.contains(id)) cnt++;
            if (cnt == 0) continue;
            int[] newItems = new int[cnt];
            double[] newUtils = new double[cnt];
            int p = 0;
            for (int i = 0; i < tr.items.length; i++) {
                if (keepSet.contains(tr.items[i])) {
                    newItems[p] = tr.items[i];
                    newUtils[p] = tr.utils[i];
                    p++;
                }
            }
            out.add(new Transaction(tr.acc, newItems, newUtils));
        }
        return mergeTransactions(out);
    }

    // =====================================================================
    // Per-level driver: builds Sec(empty) / Pri(empty) then calls Search()
    // =====================================================================

    /**
     * Mines one taxonomy level: builds D(l) via {@link #buildLevelDatabase},
     * computes {@code Elu(}&empty;{@code , w)} to derive Sec(&empty;) (skipped
     * when {@link Variant#useElu} is off), projects onto Sec(&empty;),
     * computes {@code Esu(}&empty;{@code , w)} to derive Pri(&empty;)
     * (skipped when {@link Variant#useEsu} is off), then kicks off the
     * recursive {@link #search} from the empty prefix. A no-op if D(l) or
     * the level's item set is empty.
     *
     * @param l the taxonomy level (height) to mine
     */
    private void processLevel(int l) {
        if (debug) dbg(String.format("\n=== [LEVEL %d] minU(entering) = %.6f ===", l, minU));
        List<Transaction> dl = buildLevelDatabase(l);
        if (dl.isEmpty()) {
            if (debug) dbg("  D(l) is empty, skipping level " + l);
            return;
        }

        Set<Integer> levelItems = new HashSet<>();
        for (Item it : items.values()) if (it.height == l) levelItems.add(it.id);
        if (levelItems.isEmpty()) {
            if (debug) dbg("  no items registered at height==" + l + ", skipping level " + l);
            return;
        }
        if (debug) {
            dbg("  D(l) size (" + (variant.mergeBeforeMining ? "merged" : "unmerged, one record per raw transaction")
                    + ") = " + dl.size() + " | items at this level = " + levelItems.size());
        }
        // D(l) itself must be walked to build Elu(w): when merge-before-mining is off
        // this list is longer (one record per raw transaction instead of one per
        // distinct generalised item-set), which is exactly the extra scanning cost
        // that U-TK-MLHUI-wo-merge pays for skipping that merge.
        scannedListsCount += dl.size();

        // Elu(empty, w) = sum over transactions containing w of [EU(empty,T) + Ere(empty,T)]
        //               = sum of total record EU (Muc 3.2, Elu(X,w) with X = empty)
        Map<Integer, Double> elu = new HashMap<>();
        for (Transaction tr : dl) {
            double total = tr.totalRemaining();
            for (int id : tr.items) {
                if (!levelItems.contains(id)) continue;
                elu.merge(id, total, Double::sum);
            }
        }

        // Sec(empty): if useElu is off (Baseline B), skip the Elu >= minU filter entirely --
        // every item that actually occurs at this level passes through unpruned, per the
        // spec's Baseline B definition ("bo Expected Local Utility").
        List<Integer> secEmpty = new ArrayList<>();
        for (int id : levelItems) {
            Double v = elu.get(id);           // must actually occur in the level database
            if (v == null) continue;
            if (!variant.useElu || geMinU(v)) secEmpty.add(id);
        }
        secEmpty.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));
        if (debug) {
            dbg("  Sec(empty) size = " + secEmpty.size()
                    + (variant.useElu ? " (Elu >= minU=" + minU + ")" : " (Elu pruning DISABLED, all occurring items kept)"));
            for (int id : secEmpty) dbg(String.format("    w=%d name=%s Elu=%.6f", id, items.get(id).name, elu.get(id)));
        }
        if (secEmpty.isEmpty()) return;

        Set<Integer> secSet = new HashSet<>(secEmpty);
        List<Transaction> projected = project(dl, secSet);

        // Pri(empty): if useEsu is off (Baseline C), skip Esu entirely -- Pri(empty) = Sec(empty),
        // per the spec's Baseline C definition ("bo Expected Sub-tree Utility").
        List<Integer> priEmpty;
        if (variant.useEsu) {
            // Esu(empty, w) = suffix-sum (from w's position, inclusive) since acc = 0 for X = empty
            Map<Integer, Double> esu = new HashMap<>();
            for (Transaction tr : projected) {
                double suffix = 0;
                for (int i = tr.items.length - 1; i >= 0; i--) {
                    suffix += tr.utils[i];
                    esu.merge(tr.items[i], suffix, Double::sum);
                }
            }
            priEmpty = new ArrayList<>();
            for (int id : secEmpty) {
                if (geMinU(esu.getOrDefault(id, 0.0))) priEmpty.add(id);
            }
            priEmpty.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));
            if (debug) {
                dbg("  Pri(empty) size = " + priEmpty.size() + " (Esu >= minU=" + minU + ")");
                for (int id : priEmpty) dbg(String.format("    v=%d name=%s Esu=%.6f", id, items.get(id).name, esu.get(id)));
            }
        } else {
            priEmpty = new ArrayList<>(secEmpty);
            if (debug) dbg("  Pri(empty) = Sec(empty) (Esu pruning DISABLED), size = " + priEmpty.size());
        }

        candidateCount += secEmpty.size();
        scannedListsCount += projected.size();

        search(new int[0], projected, secEmpty, priEmpty);
    }

    // =====================================================================
    // Search(X, D_X, Sec(X), Pri(X))  [minU / heap are shared instance state]
    // =====================================================================

    /**
     * {@code Search(X, D_X, Sec(X), Pri(X))} - the spec's recursive
     * depth-first search over EU (Muc 3-4). For each promising extension
     * item {@code v} in {@code priX}: builds the extended pattern Y = X
     * union {v} and its projected sub-database, offers Y's exact expected
     * utility to the shared top-k {@link #heap} (raising {@link #minU} via
     * Strategy 3 when enabled and the heap overflows size k), computes
     * Sec(Y) via Elu (or leaves it unpruned when {@link Variant#useElu} is
     * off) and Pri(Y) via Esu (or {@code Pri(Y) = Sec(Y)} when
     * {@link Variant#useEsu} is off), then recurses into
     * {@code search(Y, ...)}. Stops a branch early whenever Sec(Y) is
     * empty. {@link #minU} and {@link #heap} are shared instance state
     * across the whole recursion (and across taxonomy levels).
     *
     * @param xItems the item ids of the current search prefix X
     * @param dX     X's projected sub-database, D_X
     * @param secX   candidate extension items for X (passed Elu pruning, or unpruned)
     * @param priX   promising extension items for X (passed Esu pruning, or equal to secX); the ones actually recursed into
     */
    private void search(int[] xItems, List<Transaction> dX, List<Integer> secX, List<Integer> priX) {
        for (int v : priX) {
            int[] yItems = Arrays.copyOf(xItems, xItems.length + 1);
            yItems[yItems.length - 1] = v;

            List<Transaction> rawY = new ArrayList<>();
            double uY = 0;
            for (Transaction tr : dX) {
                int idx = tr.indexOf(v);
                if (idx < 0) continue;
                double newAcc = tr.acc + tr.utils[idx];
                uY += newAcc;
                int len = tr.items.length - idx - 1;
                int[] newItems = new int[len];
                double[] newUtils = new double[len];
                System.arraycopy(tr.items, idx + 1, newItems, 0, len);
                System.arraycopy(tr.utils, idx + 1, newUtils, 0, len);
                rawY.add(new Transaction(newAcc, newItems, newUtils));
            }

            candidateCount++;

            boolean addedToHeap = false;
            // Strategy 3: dynamic threshold raising.
            // minPatternSize gate: a size-1 Y (v extending the empty prefix) is never
            // itself an eligible result for this dataset (see minPatternSize) -- but we
            // must still fall through to build dY/secY/priY and recurse below, since
            // longer eligible patterns are only reachable by extending past it.
            boolean eligible = yItems.length >= minPatternSize;
            if (eligible && geMinU(uY)) {
                heap.add(new Pattern(yItems, uY));
                addedToHeap = true;
                if (heap.size() > k) {
                    Pattern removed = heap.poll();
                    if (variant.dynamicPromisingUpdate) {
                        double newMinU = heap.peek() != null ? heap.peek().utility : minU;
                        if (newMinU > minU) {
                            muThresholdLog.add(String.format(
                                    "candidates=%d | minU: %.6f -> %.6f | trigger=%s",
                                    candidateCount, minU, newMinU, describePattern(yItems, uY)));
                            minU = newMinU;
                        }
                    }
                    // when dynamicPromisingUpdate is disabled (Strategy 3 off), the heap is
                    // still trimmed to size k for a correct final top-k, but minU (and thus
                    // the Elu/Esu pruning bound used everywhere else) is never raised from it --
                    // this is exactly the "khong nang minU" behaviour of wo-threshold-raising.
                }
            }
            if (debug) {
                dbg(String.format("  candidate Y=%s EU=%.6f minU=%.6f addedToHeap=%s heapSize=%d",
                        describePattern(yItems, uY), uY, minU, addedToHeap, heap.size()));
            }

            List<Transaction> dY = mergeTransactions(rawY);
            scannedListsCount += dY.size();

            // Sec(Y) via Elu(Y, w) -- skipped (kept unpruned) when useElu is off
            List<Integer> secY;
            if (variant.useElu) {
                Map<Integer, Double> eluY = new HashMap<>();
                for (Transaction tr : dY) {
                    double total = tr.acc + tr.totalRemaining();
                    for (int id : tr.items) eluY.merge(id, total, Double::sum);
                }
                secY = new ArrayList<>();
                for (int id : secX) {
                    if (id == v) continue;
                    Double val = eluY.get(id);
                    if (val != null && geMinU(val)) secY.add(id);
                }
            } else {
                secY = new ArrayList<>();
                Set<Integer> occurring = new HashSet<>();
                for (Transaction tr : dY) for (int id : tr.items) occurring.add(id);
                for (int id : secX) {
                    if (id == v) continue;
                    if (occurring.contains(id)) secY.add(id);
                }
            }
            if (secY.isEmpty()) {
                if (debug) dbg("    Sec(Y) empty -> stop this branch");
                continue;
            }
            secY.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));

            Set<Integer> secYSet = new HashSet<>(secY);
            List<Transaction> dYProjected = project(dY, secYSet);

            // Pri(Y) via Esu(Y, w) -- Pri(Y) = Sec(Y) (unpruned) when useEsu is off
            List<Integer> priY;
            if (variant.useEsu) {
                Map<Integer, Double> esuY = new HashMap<>();
                for (Transaction tr : dYProjected) {
                    double suffix = 0;
                    for (int i = tr.items.length - 1; i >= 0; i--) {
                        suffix += tr.utils[i];
                        esuY.merge(tr.items[i], tr.acc + suffix, Double::sum);
                    }
                }
                priY = new ArrayList<>();
                for (int id : secY) {
                    if (geMinU(esuY.getOrDefault(id, 0.0))) priY.add(id);
                }
            } else {
                priY = new ArrayList<>(secY);
            }
            priY.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));
            if (debug) {
                dbg("    Sec(Y) size=" + secY.size() + " Pri(Y) size=" + priY.size()
                        + " Sec(Y)=" + secY + " Pri(Y)=" + priY);
            }

            search(yItems, dYProjected, secY, priY);
        }
    }

    /**
     * Formats an itemset and its expected utility for debug/log output,
     * using item display names where available.
     *
     * @param ids     the pattern's item ids
     * @param utility the pattern's expected utility, EU(X)
     * @return a human-readable {@code "{name1, name2} (EU=...)"} rendering
     */
    private String describePattern(int[] ids, double utility) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            Item it = items.get(ids[i]);
            sb.append(it != null && it.name != null ? it.name : String.valueOf(ids[i]));
        }
        sb.append("} (EU=").append(String.format("%.6f", utility)).append(")");
        return sb.toString();
    }

    /**
     * Looks up a registered item by id.
     *
     * @param id the item id to look up
     * @return the matching {@link Item}, or {@code null} if {@code id} was never registered
     */
    public Item getItem(int id) {
        return items.get(id);
    }
}
