package tkmlhui;

import java.util.*;

/**
 * Holds the taxonomy (hierarchy) tree parsed from taxonomy.txt
 * (lines: child_id,parent_id) and provides:
 *   - height(node): 0 for a true leaf, 1+max(children height) otherwise
 *   - generalize(leafId, targetLevel): walks a leaf up its ancestor chain
 *     to the first ancestor whose height >= targetLevel (handles
 *     unbalanced branches gracefully).
 */
public class Taxonomy {
    /** child id -> parent id, one entry per {@code taxonomy.txt} edge. */
    public Map<Integer, Integer> parentOf = new HashMap<>();
    /** parent id -> list of its direct children ids. */
    public Map<Integer, List<Integer>> childrenOf = new HashMap<>();
    /** Every node id (leaf or generalised) that appears on either side of any edge. */
    public Set<Integer> allNodes = new HashSet<>();

    /**
     * Registers one child-parent edge, as parsed from a {@code taxonomy.txt} line.
     *
     * @param child  the child (more specialised) item id
     * @param parent the parent (more general) item id
     */
    public void addEdge(int child, int parent) {
        parentOf.put(child, parent);
        childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
        allNodes.add(child);
        allNodes.add(parent);
    }

    /**
     * Computes height for every node reachable from the taxonomy edges.
     * leafItemIds are seeded at height 0 (they are guaranteed to be leaves
     * of the whole item space even if they never appear on the left side
     * of taxonomy.txt).
     *
     * @param leafItemIds the ids of every leaf item actually seen in the transaction database
     * @return map from item id (leaf or generalised) to its computed height
     */
    public Map<Integer, Integer> computeHeights(Set<Integer> leafItemIds) {
        Map<Integer, Integer> height = new HashMap<>();
        for (int id : leafItemIds) height.put(id, 0);
        Set<Integer> visiting = new HashSet<>();
        for (int node : allNodes) {
            computeHeightRec(node, height, visiting);
        }
        return height;
    }

    /**
     * Recursive worker for {@link #computeHeights}: post-order DFS that
     * memoises each node's height as {@code 1 + max(children height)},
     * guarding against cycles by treating a node revisited while still
     * on the current DFS path as height 0.
     *
     * @param node     the node whose height to compute
     * @param height   memo table, shared and mutated across the whole recursion
     * @param visiting nodes currently on the DFS stack (cycle guard)
     * @return the computed (and memoised) height of {@code node}
     */
    private int computeHeightRec(int node, Map<Integer, Integer> height, Set<Integer> visiting) {
        if (height.containsKey(node)) return height.get(node);
        if (visiting.contains(node)) {
            height.put(node, 0); // cycle guard
            return 0;
        }
        visiting.add(node);
        List<Integer> ch = childrenOf.get(node);
        int h;
        if (ch == null || ch.isEmpty()) {
            h = 0;
        } else {
            int mx = 0;
            for (int c : ch) mx = Math.max(mx, computeHeightRec(c, height, visiting));
            h = mx + 1;
        }
        visiting.remove(node);
        height.put(node, h);
        return h;
    }

    /**
     * First ancestor (inclusive) along leafId's parent chain whose height >= targetLevel.
     *
     * @param leafId      the leaf (or already-generalised) item id to generalise
     * @param targetLevel the taxonomy height to generalise up to
     * @param height      precomputed heights, as returned by {@link #computeHeights}
     * @return the generalised item id at (or above) {@code targetLevel}
     */
    public int generalize(int leafId, int targetLevel, Map<Integer, Integer> height) {
        int cur = leafId;
        int curH = height.getOrDefault(cur, 0);
        while (curH < targetLevel && parentOf.containsKey(cur)) {
            cur = parentOf.get(cur);
            curH = height.getOrDefault(cur, curH);
        }
        return cur;
    }

    /**
     * All ancestors of leafId, from its direct parent up to the root (used for TWU / exact-utility roll-up).
     *
     * @param id the item id whose ancestor chain to walk
     * @return ancestors of {@code id}, ordered from its direct parent up to the root
     */
    public List<Integer> ancestorsOf(int id) {
        List<Integer> res = new ArrayList<>();
        int cur = id;
        while (parentOf.containsKey(cur)) {
            cur = parentOf.get(cur);
            res.add(cur);
        }
        return res;
    }
}
