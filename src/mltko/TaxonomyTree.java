package mltko;

import java.util.*;

/**
 * Represents the "is-a" hierarchy (taxonomy) loaded from a
 * {@code taxonomy.txt}-style file (format: {@code child_id,parent_id} -
 * one edge per line, e.g. FruitHut's {@code Fruithut_taxonomy_data.txt}).
 *
 * Provides:
 *   {@link #parentOf(int)} - direct parent, or -1 if id is a root / unknown
 *   {@link #levelOf(int, Map)} - the level used by the algorithm (longest path to a leaf)
 *   {@link #leafDescendantsOf(int, Set)} - the set of original leaf items under a generalized node
 *   {@link #isAncestorOrDescendant(int, int)} - true if a and b lie on the same root-to-leaf path
 */
public final class TaxonomyTree {

    // child id -> parent id
    private final Map<Integer, Integer> parent = new HashMap<>();
    // parent id -> list of direct children ids
    private final Map<Integer, List<Integer>> children = new HashMap<>();
    // every node id seen in any edge added via addEdge
    private final Set<Integer> allNodes = new HashSet<>();

    /** Creates an empty taxonomy tree, with edges to be added via {@link #addEdge}. */
    public TaxonomyTree() {
    }

    /**
     * Adds one child-to-parent edge to the tree.
     *
     * @param child    the child node's id
     * @param parentId the parent node's id
     */
    public void addEdge(int child, int parentId) {
        parent.put(child, parentId);
        children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(child);
        allNodes.add(child);
        allNodes.add(parentId);
    }

    /**
     * Looks up a node's direct parent.
     *
     * @param id the node's id
     * @return the id of {@code id}'s direct parent, or {@code -1} if
     *         {@code id} is a root node or is not present in the tree
     */
    public int parentOf(int id) {
        return parent.getOrDefault(id, -1);
    }

    /**
     * Checks whether a node is internal (has children) rather than a leaf.
     *
     * @param id the node's id
     * @return {@code true} if {@code id} has at least one child (i.e. is
     *         a generalized/category node rather than a pure leaf)
     */
    public boolean isInternalNode(int id) {
        return children.containsKey(id) && !children.get(id).isEmpty();
    }

    /**
     * Looks up a node's direct children.
     *
     * @param id the node's id
     * @return the direct children of {@code id}, or an empty list if it
     *         has none (i.e. it is a leaf)
     */
    public List<Integer> childrenOf(int id) {
        List<Integer> kids = children.get(id);
        return kids == null ? Collections.emptyList() : kids;
    }

    /**
     * Returns every internal node in the taxonomy.
     *
     * @return the set of all node ids that have at least one child - the
     *         candidate pool for generalized items
     */
    public Set<Integer> allInternalNodes() {
        return children.keySet();
    }

    /**
     * Computes all leaf items (from the transactional dataset) that live
     * under a given node, i.e. the node's own leaf descendants that
     * actually appear in the loaded transactions.
     *
     * @param id                the (possibly internal) node whose leaf
     *                          descendants are wanted
     * @param datasetLeafItems  the set of leaf item ids that actually
     *                          appear in the dataset's transactions
     * @return the set of leaf item ids under {@code id} that are also in
     *         {@code datasetLeafItems}; if {@code id} itself has no
     *         children, this is just {@code {id}} when {@code id} is in
     *         {@code datasetLeafItems}, or empty otherwise
     */
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

    /**
     * Computes the ancestor chain of a node, from the node itself up to
     * the root.
     *
     * @param id the starting node's id
     * @return a list starting with {@code id}, followed by each successive
     *         parent up to (and including) the root; guarded against
     *         cycles by a hard cap of 10,000 hops
     */
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
     * Checks whether two items lie on the same root-to-leaf branch of the
     * taxonomy. Two such items must never be combined into the same
     * multi-level pattern, since that would produce a redundant / invalid
     * generalized itemset (an item together with its own category).
     *
     * @param a the first item's id
     * @param b the second item's id
     * @return {@code true} if {@code a == b}, {@code a} is an ancestor of
     *         {@code b}, or {@code b} is an ancestor of {@code a}
     */
    public boolean isAncestorOrDescendant(int a, int b) {
        if (a == b) return true;
        List<Integer> chainA = ancestorChain(a);
        if (chainA.contains(b)) return true;
        List<Integer> chainB = ancestorChain(b);
        return chainB.contains(a);
    }

    /**
     * Computes the level of a generalized node {@code g}, as used by
     * {@link MlTKOAlgorithm} to group items for per-level mining.
     *
     * Design note (see {@code output_level_debug.txt} for a live
     * investigation on the current dataset): the paper's Sec. 3.1 wording
     * ("length of the SHORTEST path between g and any of its leaf nodes")
     * does NOT reproduce SPMF's own published description of the
     * FruitHut dataset ("4 levels, 43 categories") when applied literally
     * - on the real taxonomy file, almost every category has both some
     * leaf products assigned directly AND deeper sub-categories, so the
     * shortest path collapses all 43 categories down to a single
     * level = 1. Verified by direct computation on
     * {@code Fruithut_taxonomy_data.txt}:
     *   shortest-path : {1: 43}            -> only 1 distinct level
     *   longest-path  : {1: 37, 2: 4, 3: 2} -> 3 distinct levels
     *                   (+ leaf level 0 = 4 distinct levels total)
     *
     * Only the LONGEST path matches "4 levels / 43 categories" exactly,
     * so that is what this method (and the algorithm) uses. The
     * shortest-path computation is kept as {@link #levelOfShortest} purely
     * for debugging/side-by-side comparison.
     *
     * @param id   the node whose level is wanted
     * @param memo a mutable memoization map, reused across calls for the
     *             same taxonomy to avoid recomputation; keyed by node id
     * @return the node's level: {@code 0} for a leaf, otherwise
     *         {@code 1 + max(levelOf(child))} over all direct children
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
     * Shortest-path level computation - the literal reading of the
     * paper's Sec. 3.1 definition ("length of the SHORTEST path ... to
     * any leaf"). Kept only for debugging/comparison; do not use this for
     * mining - see {@link #levelOf} for why this collapses incorrectly on
     * the real FruitHut taxonomy.
     *
     * @param id   the node whose (shortest-path) level is wanted
     * @param memo a mutable memoization map, reused across calls for the
     *             same taxonomy to avoid recomputation; keyed by node id
     * @return the node's shortest-path level: {@code 0} for a leaf,
     *         otherwise {@code 1 + min(levelOfShortest(child))} over all
     *         direct children
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

    /**
     * Alias for {@link #levelOf(int, Map)}, kept for clarity at call
     * sites that want to be explicit about using the longest-path
     * definition of level.
     *
     * @param id   the node whose level is wanted
     * @param memo a mutable memoization map, reused across calls for the
     *             same taxonomy
     * @return same result as {@link #levelOf(int, Map)}
     */
    public int levelOfMax(int id, Map<Integer, Integer> memo) {
        return levelOf(id, memo);
    }

    /**
     * Returns every node id known to this taxonomy.
     *
     * @return the set of all node ids (both internal and leaf) that have
     *         appeared in any edge added via {@link #addEdge}
     */
    public Set<Integer> nodes() {
        return allNodes;
    }
}
