package tkmlhui;

import java.util.*;

/**
 * TK-MLHUI (Top-k Multi-Level High Utility Itemset Mining), 2025-style
 * horizontal-database / projection-and-merge architecture.
 *
 * Implements:
 *   - Local Utility (lu) and Sub-tree Utility (su) upper bounds
 *   - Strategy 1&2: high initial minU from single items + merged transactions
 *   - Strategy 3 : dynamic threshold raising via a size-k min-heap
 *   - Strategy 4 : database projection & transaction merging at every step
 *
 * The database is processed one taxonomy level at a time, from the
 * deepest (most specialised) level down to level 0 (most general),
 * while minU is carried over (and only ever increases) across levels.
 */
public class TKMLHUIAlgo {

    /**
     * The three ablation variants compared in the paper. All three still use the
     * core lu/su pruning (Sec/Pri computation) and "merge during mining" (the
     * per-Search()-call transaction merge that builds D_Y) -- those are the base
     * search architecture and are never turned off. What differs is:
     *
     *   - initialThresholdBoost   : Strategy 1&2, computeInitialMinU() ("tang
     *                               nguong ban dau bang do ich loi danh sach muc")
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
        WO_ALL("TK-MLHUI-wo-all", false, false, false),
        WO_MERGE("TK-MLHUI-wo-merge", true, true, false);

        public final String label;
        public final boolean initialThresholdBoost;
        public final boolean dynamicPromisingUpdate;
        public final boolean mergeBeforeMining;

        Variant(String label, boolean initialThresholdBoost, boolean dynamicPromisingUpdate, boolean mergeBeforeMining) {
            this.label = label;
            this.initialThresholdBoost = initialThresholdBoost;
            this.dynamicPromisingUpdate = dynamicPromisingUpdate;
            this.mergeBeforeMining = mergeBeforeMining;
        }

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

    public Variant variant = Variant.FULL;

    // ---- input model -------------------------------------------------
    private final Map<Integer, Item> items = new HashMap<>();     // id -> Item (leaf or generalised)
    private final Taxonomy taxonomy;
    private Map<Integer, Integer> height;                          // id -> multi-level height
    private int maxLevel;

    private List<int[]> rawItemsOfTx = new ArrayList<>();          // raw leaf-level transactions
    private List<long[]> rawUtilsOfTx = new ArrayList<>();

    private int k;

    // ---- mining state --------------------------------------------------
    private long minU = 0;
    private Map<Integer, Integer> rankOf;                          // item id -> global processing rank (0 = highest TWU)

    static class Pattern {
        int[] itemIds;
        long utility;
        Pattern(int[] itemIds, long utility) { this.itemIds = itemIds; this.utility = utility; }
    }

    private final PriorityQueue<Pattern> heap =
            new PriorityQueue<>(Comparator.comparingLong(p -> p.utility)); // min-heap

    // ---- statistics / logs ---------------------------------------------
    public long candidateCount = 0;
    public long scannedListsCount = 0;
    public long executionTimeMs = 0;
    public double peakMemoryMB = 0;
    public final List<String> muThresholdLog = new ArrayList<>();

    // ---- debug tracing ---------------------------------------------------
    public boolean debug = false;
    public final List<String> debugLog = new ArrayList<>();
    private static final int DEBUG_LINE_CAP = 20000; // avoid runaway files on big datasets
    private boolean debugTruncated = false;

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

    public TKMLHUIAlgo(Taxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    // =====================================================================
    // INPUT REGISTRATION (called by the Main / loader)
    // =====================================================================

    public void registerItem(int id, String name) {
        items.computeIfAbsent(id, x -> new Item(id, name));
    }

    public void addTransaction(int[] itemIds, long[] utils) {
        rawItemsOfTx.add(itemIds);
        rawUtilsOfTx.add(utils);
    }

    public void setK(int k) {
        this.k = k;
    }

    // =====================================================================
    // MAIN ENTRY POINT
    // =====================================================================

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

    private long computeInitialMinU() {
        if (!variant.initialThresholdBoost) {
            if (debug) dbg("  Strategy 1&2 (initial threshold boost) disabled for variant " + variant.label + " -> minU starts at 0");
            return 0;
        }
        List<Long> pool = new ArrayList<>();
        // Strategy 1: exact utility of every single item (each id is its own distinct itemset)
        for (Item it : items.values()) pool.add(it.exactUtility);

        // Strategy 2: treat every RAW (leaf-level, ungeneralised) transaction as a sample itemset.
        // Transactions with an identical leaf item set are merged (summed) first, so each pool
        // entry here corresponds to exactly one distinct real itemset. We deliberately do NOT
        // repeat this across every taxonomy level: since generalisation only regroups utility
        // without changing its sum, doing so would let the same underlying transaction inject
        // several pool entries at (near-)identical values for what can amount to far fewer than
        // k truly distinct itemsets, which would make minU unsafe (over-pruning).
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

    /** Generalises every raw transaction to level l, dedupes within-transaction, sorts by rank, then merges. */
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

    /** Groups transactions with an identical item-id sequence, summing acc and utils element-wise. */
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

    /** Projects a database onto keepSet only (drops other items), preserving relative order. */
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

    public Item getItem(int id) {
        return items.get(id);
    }
}
