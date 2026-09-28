package tkmlhui;

import java.util.*;

/**
 * TK-MLHUI (Top-k Multi-Level High Utility Itemset Mining), re-implemented
 * to follow precisely:
 *   - Nguyen, Tung & Vo, "Mining top-k multi-level high utility itemsets"
 *     (Knowledge-Based Systems, Vol. 316, 2025) - the TK-MLHUI algorithm
 *     itself: Local Utility (lu) / Sub-tree Utility (su) upper bounds
 *     (Properties 4-8), Strategy 1&2 (high initial minU from single-item
 *     and merged-transaction utility), Strategy 3 (dynamic threshold
 *     raising via a size-k min-heap during {@link #search}), and
 *     Strategy 4 (horizontal-database projection & transaction merging at
 *     every recursive step).
 *
 * The database is processed one taxonomy level at a time, from the
 * deepest (most specialised) level down to level 0 (most general), while
 * minU is carried over (and only ever increases) across levels.
 *
 * The constructor-supplied {@link Taxonomy} plus this class' own
 * {@link Variant} switch directly correspond to the ablation study this
 * class supports: {@link Variant#FULL} (all strategies enabled) vs.
 * {@link Variant#WO_ALL} (Strategy 2, Strategy 3 and Strategy 4a all
 * disabled -- Strategy 1's single-item initial threshold stays on, per
 * the paper's own definition of wo-all) vs. {@link Variant#WO_MERGE}
 * (only Strategy 4's pre-mining merge disabled). See {@link Main} for
 * how all three variants are run together in one ablation study.
 */
public class TKMLHUIAlgo {

    /**
     * The three ablation variants compared in the paper. All three still use the
     * core lu/su pruning (Sec/Pri computation) and "merge during mining" (the
     * per-Search()-call transaction merge that builds D_Y) -- those are the base
     * search architecture and are never turned off. What differs is:
     *
     *   - initialThresholdBoost   : Strategy 2 only, computeInitialMinU()'s
     *                               item-list/merged-transaction pool ("tang
     *                               nguong ban dau bang do ich loi danh sach muc").
     *                               Strategy 1's single-item pool is always
     *                               included, in every variant.
     *   - dynamicPromisingUpdate  : Strategy 3, raising minU while walking the
     *                               heap during Search() ("cap nhat muc trien
     *                               vong o tung muc do")
     *   - mergeBeforeMining       : the mergeTransactions() call inside
     *                               buildLevelDatabase(), i.e. consolidating
     *                               identical generalised transactions before a
     *                               level's search even starts ("gop danh sach
     *                               muc truoc khai phai")
     */
    public enum Variant {
        FULL("TK-MLHUI", true, true, true),
        WO_MERGE("TK-MLHUI-wo-merge", true, true, false),
        WO_ALL("TK-MLHUI-wo-all", false, false, false);
        

        /** Display/file-suffix label for this variant, e.g. {@code "TK-MLHUI-wo-merge"}. */
        public final String label;
        /** Whether Strategy 2 (high initial minU from merged-transaction/item-list utility) is enabled; Strategy 1 (single items) is always on. */
        public final boolean initialThresholdBoost;
        /** Whether Strategy 3 (raising minU from the top-k heap while walking {@link #search}) is enabled. */
        public final boolean dynamicPromisingUpdate;
        /** Whether Strategy 4's pre-mining merge (consolidating identical generalised transactions per level) is enabled. */
        public final boolean mergeBeforeMining;

        /**
         * Defines one ablation variant by naming which strategies stay switched on.
         *
         * @param label                  display/file-suffix label for this variant
         * @param initialThresholdBoost  whether to enable Strategy 2 (item-list initial minU boost)
         * @param dynamicPromisingUpdate whether to enable Strategy 3 (dynamic threshold raising)
         * @param mergeBeforeMining      whether to enable Strategy 4's pre-mining transaction merge
         */
        Variant(String label, boolean initialThresholdBoost, boolean dynamicPromisingUpdate, boolean mergeBeforeMining) {
            this.label = label;
            this.initialThresholdBoost = initialThresholdBoost;
            this.dynamicPromisingUpdate = dynamicPromisingUpdate;
            this.mergeBeforeMining = mergeBeforeMining;
        }

        /**
         * Resolves a command-line argument into a {@link Variant}, matched
         * case-insensitively with {@code "-"}/{@code "_"} interchangeable.
         *
         * @param s the raw CLI token to match (e.g. {@code "wo-merge"}, {@code "WO_MERGE"})
         * @return the matching {@link Variant}, or {@code null} if {@code s} names none of them
         */
        public static Variant fromArg(String s) {
            String n = s.trim().toLowerCase().replace("_", "-");
            switch (n) {
                case "full":
                case "tk-mlhui":
                    return FULL;
                case "wo-all":
                case "woall":
                    return WO_ALL;
                case "wo-merge":
                case "womerge":
                    return WO_MERGE;
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
    /** Raw leaf-level utilities parallel to {@link #rawItemsOfTx}. */
    private List<long[]> rawUtilsOfTx = new ArrayList<>();

    /** The number of top patterns to mine, set via {@link #setK}. */
    private int k;

    // ---- mining state --------------------------------------------------
    /** The current minimum-utility threshold; only ever increases during {@link #run()}. */
    private long minU = 0;
    /** item id -> global processing rank (0 = highest priority), used to order items within a transaction. */
    private Map<Integer, Integer> rankOf;

    /** One candidate top-k pattern: an itemset plus its exact utility u(X). */
    static class Pattern {
        /** The item ids making up this pattern. */
        int[] itemIds;
        /** u(X): this pattern's exact utility. */
        long utility;
        /**
         * Creates a pattern record.
         *
         * @param itemIds the item ids making up this pattern
         * @param utility u(X), this pattern's exact utility
         */
        Pattern(int[] itemIds, long utility) { this.itemIds = itemIds; this.utility = utility; }
    }

    /** Size-k min-heap of the best patterns found so far; its minimum is the current top-k boundary. */
    private final PriorityQueue<Pattern> heap =
            new PriorityQueue<>(Comparator.comparingLong(p -> p.utility));

    // ---- statistics / logs ---------------------------------------------
    /** Total number of candidate itemsets (Search() recursive calls) visited during the last {@link #run()}. */
    public long candidateCount = 0;
    /** Total number of transaction records scanned (across all lu/su computations, projections, and merges) during the last run. */
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
    public TKMLHUIAlgo(Taxonomy taxonomy) {
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
     * Registers one raw (leaf-level) transaction.
     *
     * @param itemIds the transaction's leaf item ids
     * @param utils   utilities parallel to {@code itemIds}
     */
    public void addTransaction(int[] itemIds, long[] utils) {
        rawItemsOfTx.add(itemIds);
        rawUtilsOfTx.add(utils);
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
     * Runs the full TK-MLHUI mining pipeline: computes item heights and
     * TWU/exact-utility, establishes the global processing order, computes
     * the Strategy 1&2 initial minU, then mines each taxonomy level from
     * {@code maxLevel} down to 0 via {@link #processLevel}, carrying minU
     * forward across levels. Populates every public metric field
     * ({@link #executionTimeMs}, {@link #peakMemoryMB},
     * {@link #candidateCount}, {@link #scannedListsCount}, and
     * {@link #muThresholdLog}) as a side effect.
     *
     * Intended to be called exactly once per instance; construct a fresh
     * {@code TKMLHUIAlgo} for a repeat run.
     *
     * @return the top-k patterns found, sorted by descending utility
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

        // 3: TWU (multi-level, deduped per transaction) + exact utility (rolled up through ancestors)
        computeTwuAndExactUtility();

        // 4: global processing order, cùng level thì GTWU nhỏ hơn đứng trước (tăng dần)
        List<Integer> order = new ArrayList<>(items.keySet());
        order.sort((a, b) -> {
            int la = items.get(a).height, lb = items.get(b).height;
            if (la != lb) return Integer.compare(lb, la);      // level cao hơn trước
            long ta = items.get(a).twu, tb = items.get(b).twu;
            if (ta != tb) return Long.compare(ta, tb);          // GTWU tăng dần
            return Integer.compare(a, b);
        });
        rankOf = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rankOf.put(order.get(i), i);

        if (debug) {
            dbg("\n=== [STEP] Global processing order (TWU desc, tie-break id asc) ===");
            for (int id : order) {
                Item it = items.get(id);
                dbg(String.format("  rank=%d id=%d name=%s height=%d TWU=%d exactUtility=%d",
                        rankOf.get(id), id, it.name, it.height, it.twu, it.exactUtility));
            }
        }

        // 7: Strategy 1 & 2 -> initial minU
        minU = computeInitialMinU();
        muThresholdLog.add(String.format(
                "INIT | candidates=0 | minU: (none) -> %d | trigger=Strategy1&2 (top-k of single items + merged transactions)",
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
        result.sort((a, b) -> Long.compare(b.utility, a.utility));
        return result;
    }

    // =====================================================================
    // TWU / exact utility roll-up
    // =====================================================================

    /**
     * One full pass over the raw transactions: accumulates each leaf item's
     * (and every ancestor generalised item's) TWU and exact utility, per
     * Def. TWU / Sec 3.1 of the paper. TWU credit is deduped per
     * transaction (a repeated leaf/ancestor only contributes the
     * transaction's total utility once), while exact utility is the true
     * additive sum of {@code u(i,T)} across all occurrences.
     */
    private void computeTwuAndExactUtility() {
        for (int t = 0; t < rawItemsOfTx.size(); t++) {
            int[] tx = rawItemsOfTx.get(t);
            long[] utils = rawUtilsOfTx.get(t);
            long total = 0;
            for (long u : utils) total += u;

            Set<Integer> touched = new HashSet<>(); // dedupe TWU credit per transaction
            for (int idx = 0; idx < tx.length; idx++) {
                int leaf = tx[idx];
                long u = utils[idx];

                registerItem(leaf, null);
                Item leafItem = items.get(leaf);
                leafItem.exactUtility += u;
                if (touched.add(leaf)) leafItem.twu += total;

                for (int anc : taxonomy.ancestorsOf(leaf)) {
                    registerItem(anc, null);
                    Item ancItem = items.get(anc);
                    ancItem.exactUtility += u;      // u(GI,T) = sum of descendant-leaf utilities present
                    if (touched.add(anc)) ancItem.twu += total;
                }
            }
        }
    }

    // =====================================================================
    // Strategy 1 & 2: initial minU
    // =====================================================================

    /**
     * Computes the high initial minU: pools the exact utility of every
     * single item (Strategy 1, always included) plus, unless the variant
     * disables Strategy 2, the summed utility of every distinct
     * (leaf-level, merged) raw transaction; sorts descending, and takes
     * the k-th largest value as an admissible lower bound -- or 0 if the
     * pool has fewer than k entries (in which case using pool[k-1] would
     * risk over-pruning).
     *
     * @return the initial minU, or 0 if the pool is too small to safely apply
     */
    private long computeInitialMinU() {
        List<Long> pool = new ArrayList<>();
        // Strategy 1 (paper Sec. 4.4.1): exact utility of every single item (each id
        // is its own distinct itemset). Kept for EVERY variant, including wo-all: the
        // paper's own definition of wo-all ("applying only the pruning strategy; not
        // applying 'update promising items' strategy, 'merge before mining' strategy
        // and 'increase threshold by item lists utility' strategy") only names the
        // item-list-based extension below as disabled, not this single-item strategy.
        for (Item it : items.values()) pool.add(it.exactUtility);

        // Strategy 2 (paper Sec. 4.4.2, "increase threshold by item lists utility"):
        // treat every RAW (leaf-level, ungeneralised) transaction as a sample itemset.
        // This is the strategy wo-all specifically disables via initialThresholdBoost.
        // Transactions with an identical leaf item set are merged (summed) first, so each pool
        // entry here corresponds to exactly one distinct real itemset. We deliberately do NOT
        // repeat this across every taxonomy level: since generalisation only regroups utility
        // without changing its sum, doing so would let the same underlying transaction inject
        // several pool entries at (near-)identical values for what can amount to far fewer than
        // k truly distinct itemsets, which would make minU unsafe (over-pruning).
        if (variant.initialThresholdBoost) {
            Map<String, Long> rawGroups = new LinkedHashMap<>();
            for (int t = 0; t < rawItemsOfTx.size(); t++) {
                int[] tx = rawItemsOfTx.get(t);
                long[] utils = rawUtilsOfTx.get(t);
                Integer[] sortedIdx = new Integer[tx.length];
                for (int i = 0; i < tx.length; i++) sortedIdx[i] = i;
                Arrays.sort(sortedIdx, (a, b) -> Integer.compare(tx[a], tx[b]));
                int[] sortedItems = new int[tx.length];
                long total = 0;
                for (int i = 0; i < tx.length; i++) {
                    sortedItems[i] = tx[sortedIdx[i]];
                    total += utils[sortedIdx[i]];
                }
                String key = Arrays.toString(sortedItems);
                rawGroups.merge(key, total, Long::sum);
            }
            pool.addAll(rawGroups.values());
        } else if (debug) {
            dbg("  Strategy 2 (item-list initial threshold) disabled for variant " + variant.label);
        }

        pool.sort(Collections.reverseOrder());
        // Safety condition: minU = pool.get(k-1) is only an admissible (non-over-pruning)
        // bound when the pool contains AT LEAST k distinct valid candidate patterns
        // (each pool entry is itself a real itemset: a single item or a whole
        // generalised/merged transaction). If the pool has fewer than k entries we
        // cannot yet guarantee k patterns reach that value, so we must not raise
        // minU above 0 here; Strategy 3 will raise it safely once real search begins.
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
     * @param l the taxonomy level (height) to generalise every transaction to
     * @return the level-{@code l} database D(l): one record per (merged, if enabled) generalised transaction
     */
    private List<Transaction> buildLevelDatabase(int l) {
        List<Transaction> raw = new ArrayList<>();
        for (int t = 0; t < rawItemsOfTx.size(); t++) {
            int[] tx = rawItemsOfTx.get(t);
            long[] utils = rawUtilsOfTx.get(t);

            Map<Integer, Long> merged = new HashMap<>();
            for (int idx = 0; idx < tx.length; idx++) {
                int gen = taxonomy.generalize(tx[idx], l, height);
                merged.merge(gen, utils[idx], Long::sum);
            }
            if (merged.isEmpty()) continue;

            List<Integer> ids = new ArrayList<>(merged.keySet());
            ids.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));

            int[] itemsArr = new int[ids.size()];
            long[] utilsArr = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                itemsArr[i] = ids.get(i);
                utilsArr[i] = merged.get(ids.get(i));
            }
            raw.add(new Transaction(0L, itemsArr, utilsArr));
        }
        // Strategy 4a: merge identical generalised transactions before the level's
        // search even begins. wo-merge and wo-all both skip this: they leave D(l) as
        // one record per original transaction, so later steps (lu/su, projections,
        // scannedListsCount) all have to walk a longer, non-deduplicated list. This
        // is a pure performance knob -- the summed lu/su/utility values are identical
        // either way, so results (top-k patterns) are unaffected.
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
            long[] newUtils = new long[cnt];
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
    // Per-level driver: builds Sec(∅) / Pri(∅) then calls Search()
    // =====================================================================

    /**
     * Mines one taxonomy level: builds D(l) via {@link #buildLevelDatabase},
     * computes {@code lu(}&empty;{@code , w)} to derive Sec(&empty;), projects
     * onto Sec(&empty;), computes {@code su(}&empty;{@code , w)} to derive
     * Pri(&empty;), then kicks off the recursive {@link #search} from the
     * empty prefix. A no-op if D(l) or the level's item set is empty.
     *
     * @param l the taxonomy level (height) to mine
     */
    private void processLevel(int l) {
        if (debug) dbg(String.format("\n=== [LEVEL %d] minU(entering) = %d ===", l, minU));
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
        // D(l) itself must be walked to build lu(w): when merge-before-mining is off
        // this list is longer (one record per raw transaction instead of one per
        // distinct generalised item-set), which is exactly the extra scanning cost
        // that TK-MLHUI-wo-all / TK-MLHUI-wo-merge pay for skipping that merge.
        scannedListsCount += dl.size();

        // lu(∅, w) = sum over transactions containing w of [u(∅,T) + re(∅,T)] = sum of total record utility
        Map<Integer, Long> lu = new HashMap<>();
        for (Transaction tr : dl) {
            long total = tr.totalRemaining();
            for (int id : tr.items) {
                if (!levelItems.contains(id)) continue;
                lu.merge(id, total, Long::sum);
            }
        }

        List<Integer> secEmpty = new ArrayList<>();
        for (int id : levelItems) {
            Long v = lu.get(id);           // must actually occur in the level database
            if (v != null && v >= minU) secEmpty.add(id);
        }
        secEmpty.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));
        if (debug) {
            dbg("  Sec(empty) size = " + secEmpty.size() + " (lu >= minU=" + minU + ")");
            for (int id : secEmpty) dbg(String.format("    w=%d name=%s lu=%d", id, items.get(id).name, lu.get(id)));
        }
        if (secEmpty.isEmpty()) return;

        Set<Integer> secSet = new HashSet<>(secEmpty);
        List<Transaction> projected = project(dl, secSet);

        // su(∅, w) = suffix-sum (from w's position, inclusive) since acc = 0 for X = ∅
        Map<Integer, Long> su = new HashMap<>();
        for (Transaction tr : projected) {
            long suffix = 0;
            for (int i = tr.items.length - 1; i >= 0; i--) {
                suffix += tr.utils[i];
                su.merge(tr.items[i], suffix, Long::sum);
            }
        }

        List<Integer> priEmpty = new ArrayList<>();
        for (int id : secEmpty) {
            if (su.getOrDefault(id, 0L) >= minU) priEmpty.add(id);
        }
        priEmpty.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));
        if (debug) {
            dbg("  Pri(empty) size = " + priEmpty.size() + " (su >= minU=" + minU + ")");
            for (int id : priEmpty) dbg(String.format("    v=%d name=%s su=%d", id, items.get(id).name, su.get(id)));
        }

        candidateCount += secEmpty.size();
        scannedListsCount += projected.size();

        search(new int[0], projected, secEmpty, priEmpty);
    }

    // =====================================================================
    // Search(X, D_X, Sec(X), Pri(X))  [minU / heap are shared instance state]
    // =====================================================================

    /**
     * {@code Search(X, D_X, Sec(X), Pri(X))} - the paper's recursive
     * depth-first search. For each promising extension item {@code v} in
     * {@code priX}: builds the extended pattern Y = X union {v} and its
     * projected sub-database, offers Y's exact utility to the shared
     * top-k {@link #heap} (raising {@link #minU} via Strategy 3 when
     * enabled and the heap overflows size k), computes Sec(Y) via lu and
     * Pri(Y) via su, then recurses into {@code search(Y, ...)}. Stops a
     * branch early whenever Sec(Y) is empty. {@link #minU} and
     * {@link #heap} are shared instance state across the whole recursion
     * (and across taxonomy levels).
     *
     * @param xItems the item ids of the current search prefix X
     * @param dX     X's projected sub-database, D_X
     * @param secX   candidate extension items for X (passed lu pruning)
     * @param priX   promising extension items for X (passed su pruning); the ones actually recursed into
     */
    private void search(int[] xItems, List<Transaction> dX, List<Integer> secX, List<Integer> priX) {
        for (int v : priX) {
            int[] yItems = Arrays.copyOf(xItems, xItems.length + 1);
            yItems[yItems.length - 1] = v;

            List<Transaction> rawY = new ArrayList<>();
            long uY = 0;
            for (Transaction tr : dX) {
                int idx = tr.indexOf(v);
                if (idx < 0) continue;
                long newAcc = tr.acc + tr.utils[idx];
                uY += newAcc;
                int len = tr.items.length - idx - 1;
                int[] newItems = new int[len];
                long[] newUtils = new long[len];
                System.arraycopy(tr.items, idx + 1, newItems, 0, len);
                System.arraycopy(tr.utils, idx + 1, newUtils, 0, len);
                rawY.add(new Transaction(newAcc, newItems, newUtils));
            }

            candidateCount++;

            boolean addedToHeap = false;
            // Strategy 3: dynamic threshold raising
            if (uY >= minU) {
                heap.add(new Pattern(yItems, uY));
                addedToHeap = true;
                if (heap.size() > k) {
                    Pattern removed = heap.poll();
                    if (variant.dynamicPromisingUpdate) {
                        long newMinU = heap.peek() != null ? heap.peek().utility : minU;
                        if (newMinU > minU) {
                            muThresholdLog.add(String.format(
                                    "candidates=%d | minU: %d -> %d | trigger=%s",
                                    candidateCount, minU, newMinU, describePattern(yItems, uY)));
                            minU = newMinU;
                        }
                    }
                    // when dynamicPromisingUpdate is disabled (Strategy 3 off), the heap is
                    // still trimmed to size k for a correct final top-k, but minU (and thus
                    // the lu/su pruning bound used everywhere else) is never raised from it --
                    // this is exactly the "no update promising items" behaviour of wo-all.
                }
            }
            if (debug) {
                dbg(String.format("  candidate Y=%s uY=%d minU=%d addedToHeap=%s heapSize=%d",
                        describePattern(yItems, uY), uY, minU, addedToHeap, heap.size()));
            }

            List<Transaction> dY = mergeTransactions(rawY);
            scannedListsCount += dY.size();

            // Sec(Y) via lu(Y, w)
            Map<Integer, Long> luY = new HashMap<>();
            for (Transaction tr : dY) {
                long total = tr.acc + tr.totalRemaining();
                for (int id : tr.items) luY.merge(id, total, Long::sum);
            }
            List<Integer> secY = new ArrayList<>();
            for (int id : secX) {
                if (id == v) continue;
                Long val = luY.get(id);
                if (val != null && val >= minU) secY.add(id);
            }
            if (secY.isEmpty()) {
                if (debug) dbg("    Sec(Y) empty -> stop this branch");
                continue;
            }
            secY.sort(Comparator.comparingInt(id -> rankOf.getOrDefault(id, Integer.MAX_VALUE)));

            Set<Integer> secYSet = new HashSet<>(secY);
            List<Transaction> dYProjected = project(dY, secYSet);

            // Pri(Y) via su(Y, w)
            Map<Integer, Long> suY = new HashMap<>();
            for (Transaction tr : dYProjected) {
                long suffix = 0;
                for (int i = tr.items.length - 1; i >= 0; i--) {
                    suffix += tr.utils[i];
                    suY.merge(tr.items[i], tr.acc + suffix, Long::sum);
                }
            }
            List<Integer> priY = new ArrayList<>();
            for (int id : secY) {
                if (suY.getOrDefault(id, 0L) >= minU) priY.add(id);
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
     * Formats an itemset and its utility for debug/log output, using item
     * display names where available.
     *
     * @param ids     the pattern's item ids
     * @param utility the pattern's utility, u(X)
     * @return a human-readable {@code "{name1, name2} (u=...)"} rendering
     */
    private String describePattern(int[] ids, long utility) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            Item it = items.get(ids[i]);
            sb.append(it != null && it.name != null ? it.name : String.valueOf(ids[i]));
        }
        sb.append("} (u=").append(utility).append(")");
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
