package mltko;

import java.util.*;

/**
 * Represents the "is-a" hierarchy loaded from Fruithut_taxonomy_data.txt
 * (format: child_id,parent_id  -- one edge per line).
 *
 * Provides:
 *   - parentOf(id)                : direct parent, or -1 if id is a root / unknown
 *   - depthOf(id)                 : distance to the deepest leaf below id (0 for a pure leaf)
 *   - leafDescendantsOf(id)       : the set of original leaf items under a generalized node
 *   - isAncestorOrDescendant(a,b) : true if a and b lie on the same root-to-leaf path
 */
public final class TaxonomyTree {

    private final Map<Integer, Integer> parent = new HashMap<>();      // child -> parent
    private final Map<Integer, List<Integer>> children = new HashMap<>(); // parent -> children
    private final Set<Integer> allNodes = new HashSet<>();

    public void addEdge(int child, int parentId) {
        parent.put(child, parentId);
        children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(child);
        allNodes.add(child);
        allNodes.add(parentId);
    }

    public int parentOf(int id) {
        return parent.getOrDefault(id, -1);
    }

    public boolean isInternalNode(int id) {
        return children.containsKey(id) && !children.get(id).isEmpty();
    }

    /** Direct children of a node (empty list if none / node is a leaf). */
    public List<Integer> childrenOf(int id) {
        List<Integer> kids = children.get(id);
        return kids == null ? Collections.emptyList() : kids;
    }

    public Set<Integer> allInternalNodes() {
        return children.keySet();
    }

    /** All leaf items (from the transactional dataset) that live under node `id`. */
    public Set<Integer> leafDescendantsOf(int id, Set<Integer> datasetLeafItems) {
        Set<Integer> result = new HashSet<>();
        if (!children.containsKey(id)) {
            // id itself might be a leaf item id present in dataset
            if (datasetLeafItems.contains(id)) result.add(id);
            return result;
        }
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(id);
        while (!stack.isEmpty()) {
            int cur = stack.pop();
            List<Integer> kids = children.get(cur);
            if (kids == null || kids.isEmpty()) {
                if (datasetLeafItems.contains(cur)) result.add(cur);
            } else {
                for (int c : kids) stack.push(c);
            }
        }
        return result;
    }

    /** Ancestor chain of id, including id itself, up to the root. */
    private List<Integer> ancestorChain(int id) {
        List<Integer> chain = new ArrayList<>();
        int cur = id;
        int guard = 0;
        while (cur != -1 && guard++ < 10_000) {
            chain.add(cur);
            cur = parent.getOrDefault(cur, -1);
        }
        return chain;
    }

    /**
     * True if a is an ancestor of b, b is an ancestor of a, or a == b.
     * Two items on the same root-to-leaf branch must never be combined
     * into the same multi-level pattern (it would be a redundant / invalid
     * generalized itemset).
     */
    public boolean isAncestorOrDescendant(int a, int b) {
        if (a == b) return true;
        List<Integer> chainA = ancestorChain(a);
        if (chainA.contains(b)) return true;
        List<Integer> chainB = ancestorChain(b);
        return chainB.contains(a);
    }

    /**
     * Level of a generalized node g, used by the algorithm.
     *
     * FIXED (see output_level_debug.txt investigation): the paper's Sec. 3.1
     * wording ("length of the SHORTEST path between g and any of its leaf
     * nodes") does NOT reproduce SPMF's own published description of the
     * Fruithut dataset ("4 levels, 43 categories") when applied literally --
     * on the real taxonomy file, almost every category has both some leaf
     * products assigned directly AND deeper sub-categories, so the shortest
     * path collapses all 43 categories down to a single level=1. Verified by
     * direct computation on Fruithut_taxonomy_data.txt:
     *   shortest-path : {1: 43}                      -> only 1 distinct level
     *   longest-path  : {1: 37, 2: 4, 3: 2}           -> 3 distinct levels
     *                   (+ leaf level 0 = 4 distinct levels total)
     * Only the LONGEST path matches "4 levels / 43 categories" exactly, so
     * that is what this method (and the algorithm) now uses. The old
     * shortest-path computation is kept as levelOfShortest() purely for
     * debugging/side-by-side comparison -- see ResultWriter.writeLevelDebug.
     */
    public int levelOf(int id, Map<Integer, Integer> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        List<Integer> kids = children.get(id);
        int lvl;
        if (kids == null || kids.isEmpty()) {
            lvl = 0;
        } else {
            int max = 0;
            for (int c : kids) max = Math.max(max, levelOf(c, memo));
            lvl = max + 1;
        }
        memo.put(id, lvl);
        return lvl;
    }

    /**
     * Shortest-path level computation -- the literal reading of the paper's
     * Sec. 3.1 definition ("length of the SHORTEST path ... to any leaf").
     * Kept ONLY for debugging/comparison; do not use for mining -- see the
     * note on levelOf() above for why this collapses incorrectly on the
     * real Fruithut taxonomy.
     */
    public int levelOfShortest(int id, Map<Integer, Integer> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        List<Integer> kids = children.get(id);
        int lvl;
        if (kids == null || kids.isEmpty()) {
            lvl = 0;
        } else {
            int min = Integer.MAX_VALUE;
            for (int c : kids) min = Math.min(min, levelOfShortest(c, memo));
            lvl = min + 1;
        }
        memo.put(id, lvl);
        return lvl;
    }

    /** Alias kept for clarity at call sites that want to be explicit about using the longest-path definition. */
    public int levelOfMax(int id, Map<Integer, Integer> memo) {
        return levelOf(id, memo);
    }

    public Set<Integer> nodes() {
        return allNodes;
    }
}
