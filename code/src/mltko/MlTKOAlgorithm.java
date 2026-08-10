package mltko;

import java.util.*;

/**
 * Top-K Multi-Level High Utility Pattern Mining (mlTKO), re-implemented to
 * follow precisely:
 *   - Le, Nguyen, Nguyen, Kozierkiewicz, Tung. "Extracting Top-k High Utility
 *     Patterns from Multi-level Transaction Databases" (ACIIDS 2023) -- the
 *     mlTKO algorithm itself, Sections 3 and 4 (initial-mu strategy, taxonomy
 *     definitions, level-wise multi-level pattern restriction).
 *   - Liu & Qu, "Mining High Utility Itemsets without Candidate Generation"
 *     (CIKM 2012) -- the utility-list structure and CONSTRUCT/join procedure.
 *   - Fournier-Viger et al., "FHM: Faster High-Utility Itemset Mining using
 *     Estimated Utility Co-occurrence Pruning" -- the EUCS structure and the
 *     EUCP pruning check inside Search().
 *   - Tseng, Wu, Fournier-Viger, Yu, "Efficient Algorithms for Mining Top-K
 *     High Utility Itemsets" (TKDE 2016) -- the RUC threshold-raising
 *     strategy and the TKO one-phase search skeleton that mlTKO extends.
 *
 * Two variants directly correspond to the paper's own ablation study
 * (Section 5): mlTKO (useEUCP=true) vs. mlTKO-nop (useEUCP=false). A third,
 * optional "wo-merge" mode is NOT part of the original paper -- it is kept
 * only as an extra, clearly-labelled experiment (see useMergeOptimization).
 */
public final class MlTKOAlgorithm {

    private final List<Transaction> transactions;
    private final TaxonomyTree taxonomy;
    private final Map<Integer, String> itemNames;
    private final int k;
    private final boolean useEUCP;              // paper's mlTKO vs mlTKO-nop switch
    private final boolean useMergeOptimization; // NOT in the paper; extra/bonus variant only

    // ---- metrics (populated by run()) ----
    public long executionTimeMs;
    public double peakMemoryMB;
    public long candidatesGenerated;
    public long scannedItemLists; // number of CONSTRUCT() calls
    public long kListOfferAttempts; // number of times u(Px) >= mu held (a candidate was actually tested against kList)

    // ---- diagnostic stats ----
    public int numLeafItems;
    public int numGeneralizedNodes;
    public int numSurvivedLeaf;
    public int numSurvivedGen;
    public long initialMu;
    public long finalMu;

    public final MuThresholdLog muLog = new MuThresholdLog();

    public MlTKOAlgorithm(List<Transaction> transactions, TaxonomyTree taxonomy,
                           Map<Integer, String> itemNames, int k,
                           boolean useEUCP, boolean useMergeOptimization) {
        this.transactions = transactions;
        this.taxonomy = taxonomy;
        this.itemNames = itemNames;
        this.k = k;
        this.useEUCP = useEUCP;
        this.useMergeOptimization = useMergeOptimization;
    }

    public List<Pattern> run() {
        candidatesGenerated = 0;
        scannedItemLists = 0;
        kListOfferAttempts = 0;

        List<java.lang.management.MemoryPoolMXBean> pools =
                java.lang.management.ManagementFactory.getMemoryPoolMXBeans();
        for (java.lang.management.MemoryPoolMXBean pool : pools) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.isValid()) {
                try { pool.resetPeakUsage(); } catch (UnsupportedOperationException ignored) { }
            }
        }

        long t0 = System.nanoTime();

        // =========================================================
        // SCAN 1: TWU(i) and u(i) for every leaf item i in I
        // (mlTKO paper, Def. TWU / Sec 3.1; only leaf items, per Sec 4.1)
        // =========================================================
        Set<Integer> leafItems = new HashSet<>();
        for (Transaction tx : transactions) {
            for (int it : tx.items) leafItems.add(it);
        }

        Map<Integer, Long> twuLeaf = new HashMap<>();
        Map<Integer, Long> uLeaf = new HashMap<>();
        List<Map<Integer, Long>> txItemUtilMaps = new ArrayList<>(transactions.size());
        for (Transaction tx : transactions) {
            Map<Integer, Long> m = new HashMap<>();
            for (int i = 0; i < tx.items.length; i++) {
                int item = tx.items[i];
                long util = tx.utils[i];
                m.put(item, util);
                twuLeaf.merge(item, tx.transactionUtility, Long::sum);
                uLeaf.merge(item, util, Long::sum);
            }
            txItemUtilMaps.add(m);
        }
        this.numLeafItems = leafItems.size();

        // =========================================================
        // Initial mu -- Section 4.1 of the mlTKO paper.
        // "when the TWUs and utility of ALL i in I are obtained ... sort
        //  {TWU(i):u(i)} pairs descending by TWU ... if k>=|I|, mu=pair[m].u
        //  (last element); otherwise mu=pair[k].u."
        // NOTE: this uses leaf items I ONLY -- not generalized items G.
        // =========================================================
        List<Integer> leafIdList = new ArrayList<>(twuLeaf.keySet());
        leafIdList.sort((a, b) -> Long.compare(twuLeaf.get(b), twuLeaf.get(a))); // TWU desc

        long mu;
        int m = leafIdList.size();
        if (m == 0) {
            mu = 0;
        } else if (k >= m) {
            mu = uLeaf.getOrDefault(leafIdList.get(m - 1), 0L); // pair[m].u
        } else {
            mu = uLeaf.getOrDefault(leafIdList.get(k - 1), 0L); // pair[k].u (1-indexed -> k-1)
        }
        if (mu < 0) mu = 0;
        this.initialMu = mu;
        muLog.recordInitial(mu);

        // =========================================================
        // Generalized items G: taxonomy internal nodes with >=1 leaf
        // descendant present in the dataset. u(g,Tq) = sum of u(i,Tq) for
        // ALL leaf descendants i of g present in Tq (paper Def., C(g,tau)
        // is "all child nodes (descendants)", i.e. the whole subtree).
        // =========================================================
        Map<Integer, Set<Integer>> descendantsCache = new HashMap<>();
        Set<Integer> generalizedNodes = new HashSet<>();
        for (int g : taxonomy.allInternalNodes()) {
            Set<Integer> desc = descendantsCache.computeIfAbsent(g, id -> taxonomy.leafDescendantsOf(id, leafItems));
            if (desc.isEmpty()) continue;

            // OPTIONAL / NOT IN PAPER: merge-optimisation to drop a node
            // whose leaf-descendant set is identical to its single child's.
            if (useMergeOptimization) {
                List<Integer> kids = taxonomy.childrenOf(g);
                if (kids.size() == 1) {
                    Set<Integer> childDesc = descendantsCache.computeIfAbsent(kids.get(0),
                            id -> taxonomy.leafDescendantsOf(id, leafItems));
                    if (childDesc.size() == desc.size()) continue;
                }
            }
            generalizedNodes.add(g);
        }
        this.numGeneralizedNodes = generalizedNodes.size();

        // DIAG: raw level histogram over ALL internal nodes (unfiltered by
        // mu), to check whether the taxonomy genuinely has only one
        // generalized tier above the leaves, or whether deeper categories
        // exist but are being mis-grouped.
        Map<Integer, Long> twuGen = new HashMap<>();
        Map<Integer, Long> uGen = new HashMap<>();
        for (int g : generalizedNodes) {
            Set<Integer> desc = descendantsCache.get(g);
            for (int t = 0; t < transactions.size(); t++) {
                Map<Integer, Long> itemUtil = txItemUtilMaps.get(t);
                long sum = 0;
                for (int d : desc) {
                    Long v = itemUtil.get(d);
                    if (v != null) sum += v;
                }
                if (sum > 0) {
                    Transaction tx = transactions.get(t);
                    twuGen.merge(g, tx.transactionUtility, Long::sum);
                    uGen.merge(g, sum, Long::sum);
                }
            }
        }

        // =========================================================
        // Filter J' (leaf items) and G' (generalized items) by TWU >= mu
        // (mlTKO paper Sec 4.1: "unpromising items discarded"; applied to
        //  both leaf and generalized items using the mu computed above).
        // =========================================================
        Set<Integer> survivedLeaf = new HashSet<>();
        for (Map.Entry<Integer, Long> e : twuLeaf.entrySet()) {
            if (e.getValue() >= mu) survivedLeaf.add(e.getKey());
        }
        Set<Integer> survivedGen = new HashSet<>();
        for (Map.Entry<Integer, Long> e : twuGen.entrySet()) {
            if (e.getValue() >= mu) survivedGen.add(e.getKey());
        }
        this.numSurvivedLeaf = survivedLeaf.size();
        this.numSurvivedGen = survivedGen.size();

        Set<Integer> survived = new HashSet<>();
        survived.addAll(survivedLeaf);
        survived.addAll(survivedGen);

        // TWU lookup used only as the sort key within each level (see below).
        Map<Integer, Long> twuAll = new HashMap<>();
        twuAll.putAll(twuLeaf);
        twuAll.putAll(twuGen);

        // =========================================================
        // Group survived items by taxonomy level (leaf items = level 0;
        // level = LONGEST path to a leaf -- see TaxonomyTree.levelOf() for why
        // this, not the literal "shortest path" wording of Sec 3.1, is what
        // actually reproduces SPMF's documented level count on the real
        // Fruithut taxonomy). A multi-level pattern only ever combines items
        // from the SAME level, so the Utility-Lists / EUCS used for search
        // must be built SEPARATELY per level -- NOT from one shared global
        // order.
        //
        // BUG FIXED HERE: a previous version built ONE combined
        // Utility-List set across leaf items AND generalized nodes
        // together, in a single global TWU order. Since u(g,Tq) for a
        // generalized node already equals the SUM of its leaf
        // descendants' utility in Tq, putting a leaf item i and its own
        // ancestor g into the same per-transaction "extended" map double
        // counts that utility (once via i, again via g). That inflated
        // every sumRutil far above the true remaining utility, so the
        // pruning test (sumIutil + sumRutil < mu) almost never fired and
        // the search exploded into millions of candidates instead of
        // being cut down like the paper's reported results.
        // =========================================================
        Map<Integer, Integer> levelMemo = new HashMap<>();
        Map<Integer, List<Integer>> levelMap = new HashMap<>();
        int maxLevel = 0;
        for (int id : survived) {
            int lvl = survivedLeaf.contains(id) ? 0 : taxonomy.levelOf(id, levelMemo);
            levelMap.computeIfAbsent(lvl, d -> new ArrayList<>()).add(id);
            maxLevel = Math.max(maxLevel, lvl);
        }

        TopKManager topK = new TopKManager(k);
        long[] muHolder = new long[]{mu};

        // Algorithm 1, line 10: FOREACH level l = |tau| .. 0 -- traverse from
        // the most general level (closest to root, l = maxLevel) DOWN to the
        // leaf level (l = 0). mu is shared across all levels via muHolder, so
        // processing general levels first lets mu rise aggressively BEFORE
        // the algorithm reaches the leaf level, which is by far the largest
        // search space. Doing it in the opposite order (leaf first) leaves mu
        // weak for the biggest part of the search and explodes candidates.
        for (int level = maxLevel; level >= 0; level--) {
            List<Integer> levelItems = levelMap.get(level);
            if (levelItems == null || levelItems.isEmpty()) continue;

            // Local ascending-TWU order, scoped to THIS level only.
            levelItems.sort(Comparator.comparingLong(id -> twuAll.getOrDefault(id, 0L)));
            Map<Integer, Integer> localRank = new HashMap<>();
            for (int i = 0; i < levelItems.size(); i++) localRank.put(levelItems.get(i), i);

            Map<Integer, UtilityList> levelUL = new HashMap<>();
            for (int id : levelItems) levelUL.put(id, new UtilityList(id));
            EUCSMatrix levelEucs = new EUCSMatrix();

            boolean isLeafLevel = (level == 0);

            // SCAN 2 (per level): build Utility-Lists + EUCS using ONLY
            // this level's items, so iutil/rutil never mix a node with
            // any of its own ancestors/descendants.
            for (int t = 0; t < transactions.size(); t++) {
                Transaction tx = transactions.get(t);
                Map<Integer, Long> itemUtil = txItemUtilMaps.get(t);

                Map<Integer, Long> extended = new HashMap<>();
                if (isLeafLevel) {
                    for (int id : levelItems) {
                        Long v = itemUtil.get(id);
                        if (v != null) extended.put(id, v);
                    }
                } else {
                    for (int g : levelItems) {
                        Set<Integer> desc = descendantsCache.get(g);
                        long sum = 0;
                        for (int d : desc) {
                            Long v = itemUtil.get(d);
                            if (v != null) sum += v;
                        }
                        if (sum > 0) extended.put(g, sum);
                    }
                }
                if (extended.isEmpty()) continue;

                List<Integer> presentSorted = new ArrayList<>(extended.keySet());
                presentSorted.sort(Comparator.comparingInt(localRank::get));

                long[] suffix = new long[presentSorted.size() + 1];
                for (int i = presentSorted.size() - 1; i >= 0; i--) {
                    suffix[i] = suffix[i + 1] + extended.get(presentSorted.get(i));
                }
                for (int i = 0; i < presentSorted.size(); i++) {
                    int item = presentSorted.get(i);
                    levelUL.get(item).addElement(new Element(tx.tid, extended.get(item), suffix[i + 1]));
                }
                for (int i = 0; i < presentSorted.size(); i++) {
                    for (int j = i + 1; j < presentSorted.size(); j++) {
                        levelEucs.add(presentSorted.get(i), presentSorted.get(j), tx.transactionUtility);
                    }
                }
            }

            List<UtilityList> extensions = new ArrayList<>();
            for (int id : levelItems) extensions.add(levelUL.get(id));

            long muBefore = muHolder[0];
            long candBefore = candidatesGenerated;
            long scanBefore = scannedItemLists;
            long offerBefore = kListOfferAttempts;

            search(null, null, extensions, muHolder, levelEucs, topK);
        }

        this.finalMu = muHolder[0];
        long t1 = System.nanoTime();
        executionTimeMs = (t1 - t0) / 1_000_000L;

        long peakBytes = 0L;
        for (java.lang.management.MemoryPoolMXBean pool : pools) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.isValid()) {
                java.lang.management.MemoryUsage usage = pool.getPeakUsage();
                if (usage != null) peakBytes += usage.getUsed();
            }
        }
        peakMemoryMB = peakBytes / (1024.0 * 1024.0);

        return topK.getSortedDescending();
    }

    /**
     * Search(P, E(P), mu, EUCS, kList) -- FHM's Algorithm 2 / the TKO
     * paper's TopK-HUI-Search, extended with the taxonomy same-level
     * restriction (never combine an item with its own ancestor/descendant;
     * enforced structurally here since same-level items never lie on the
     * same root-to-leaf path in a well-formed taxonomy, kept as a safety
     * check regardless).
     */
    private void search(int[] prefixItemset, UtilityList prefixUL, List<UtilityList> extensions,
                         long[] muHolder, EUCSMatrix eucs, TopKManager topK) {

        for (int xi = 0; xi < extensions.size(); xi++) {
            UtilityList px = extensions.get(xi);
            candidatesGenerated++;

            int[] pxItemset = append(prefixItemset, px.item);

            if (px.sumIutil >= muHolder[0]) {
                kListOfferAttempts++;
                Pattern p = new Pattern(pxItemset, px.sumIutil);
                long before = muHolder[0];
                muHolder[0] = topK.offer(p, muHolder[0]);
                if (muHolder[0] != before) {
                    muLog.recordRaise(candidatesGenerated, before, muHolder[0], pxItemset);
                }
            }

            if (px.sumIutil + px.sumRutil >= muHolder[0]) {
                List<UtilityList> childExtensions = new ArrayList<>();
                for (int yi = xi + 1; yi < extensions.size(); yi++) {
                    UtilityList py = extensions.get(yi);

                    if (taxonomy.isAncestorOrDescendant(px.item, py.item)) continue;

                    if (useEUCP) {
                        long co = eucs.get(px.item, py.item);
                        if (co < muHolder[0]) continue; // EUCP pruning (FHM)
                    }

                    UtilityList pxy = construct(prefixUL, px, py);
                    scannedItemLists++;
                    if (pxy != null && !pxy.elements.isEmpty()) {
                        childExtensions.add(pxy);
                    }
                }
                if (!childExtensions.isEmpty()) {
                    search(pxItemset, px, childExtensions, muHolder, eucs, topK);
                }
            }
        }
    }

    private int[] append(int[] base, int item) {
        if (base == null) return new int[]{item};
        int[] r = Arrays.copyOf(base, base.length + 1);
        r[base.length] = item;
        return r;
    }

    /**
     * CONSTRUCT(P.UL, Px.UL, Py.UL) -> Pxy.UL
     * (HUI-Miner Algorithm 1 / FHM Algorithm 3 -- identical procedure).
     */
    private UtilityList construct(UtilityList pUL, UtilityList pxUL, UtilityList pyUL) {
        UtilityList result = new UtilityList(pyUL.item);
        List<Element> ex = pxUL.elements;
        List<Element> ey = pyUL.elements;
        int i = 0, j = 0;
        while (i < ex.size() && j < ey.size()) {
            Element eX = ex.get(i);
            Element eY = ey.get(j);
            if (eX.tid == eY.tid) {
                long iutil;
                if (pUL == null) {
                    iutil = eX.iutil + eY.iutil;
                } else {
                    Element eP = findByTid(pUL.elements, eX.tid);
                    long pIutil = (eP == null) ? 0L : eP.iutil;
                    iutil = eX.iutil + eY.iutil - pIutil;
                }
                result.addElement(new Element(eX.tid, iutil, eY.rutil));
                i++; j++;
            } else if (eX.tid < eY.tid) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    private Element findByTid(List<Element> list, int tid) {
        int lo = 0, hi = list.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Element e = list.get(mid);
            if (e.tid == tid) return e;
            if (e.tid < tid) lo = mid + 1; else hi = mid - 1;
        }
        return null;
    }
}
