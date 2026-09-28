package utkmlhui;

import java.util.*;

/**
 * Holds the taxonomy (hierarchy) parsed from {@code taxonomy.txt}
 * (lines: {@code child_id,parent_id,weight}) as a WEIGHTED, MULTI-PARENT
 * DAG (Bao cao Chuong 3, Dinh nghia 3.1) instead of the earlier single-parent
 * tree: a child item may have several direct parents at once (at either tier
 * of the hierarchy -- leaf->variant and variant->lemma), each edge (c, g)
 * carrying its own confidence weight(c -> g) in (0,1].
 *
 * Provides:
 *   - height(node): 0 for a true leaf, 1+max(children height) otherwise.
 *     Unaffected by multi-parent-ness, since height only ever depends on a
 *     node's OWN children, never on how many parents point to it.
 *   - computeTransactionEU(...): the tiered, weighted roll-up of EU per
 *     transaction (Dinh nghia 3.3-3.4), used by UTKMLHUIAlgo both for the
 *     global EU(id)/EGTWU accumulation and for building each level's
 *     projected database.
 *
 * When every edge weight is 1.0 and every child has exactly one parent, this
 * degenerates back to the original single-parent tree model (Dinh nghia 2
 * goc) -- so nothing here breaks datasets that happen to still be trees.
 */
public class Taxonomy {

    /** One taxonomy edge: the id at the other end, and this edge's weight(c -> g) in (0,1]. */
    public static class Edge {
        /** The id at the other end of this edge (a parent id when stored under a child, or vice versa). */
        public final int otherId;
        /** weight(c -> g) in (0,1], the confidence that the child truly belongs to this parent (Dinh nghia 3.1). */
        public final double weight;

        /**
         * Creates one taxonomy edge endpoint.
         *
         * @param otherId the id at the other end of the edge
         * @param weight  the edge's weight(c -> g), in (0,1]
         */
        public Edge(int otherId, double weight) {
            this.otherId = otherId;
            this.weight = weight;
        }
    }

    /** child id -> list of (parent id, weight) -- Dinh nghia 3.1: a child may have several direct parents. */
    public Map<Integer, List<Edge>> parentsOf = new HashMap<>();
    /** parent id -> list of (child id, weight) -- Children(g) of Dinh nghia 3.1. */
    public Map<Integer, List<Edge>> childrenOf = new HashMap<>();
    /** Every node id (leaf or generalised) that appears on either side of any edge. */
    public Set<Integer> allNodes = new HashSet<>();

    /**
     * Registers one weighted child-parent edge, as parsed from one
     * {@code taxonomy.txt} line ({@code child_id,parent_id,weight}).
     * Calling this more than once for the same child is exactly how
     * multi-parent-ness is expressed -- each call adds one more parent to
     * {@code child}, it never overwrites a previous one (Dinh nghia 3.1).
     *
     * @param child  the child (more specialised) item id
     * @param parent the parent (more general) item id
     * @param weight weight(child -> parent), expected in (0,1]
     */
    public void addEdge(int child, int parent, double weight) {
        parentsOf.computeIfAbsent(child, k -> new ArrayList<>()).add(new Edge(parent, weight));
        childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(new Edge(child, weight));
        allNodes.add(child);
        allNodes.add(parent);
    }

    /**
     * Computes height for every node reachable from the taxonomy edges.
     * leafItemIds are seeded at height 0 (they are guaranteed to be leaves
     * of the whole item space even if they never appear on the left side
     * of taxonomy.txt). Multi-parent-ness does not affect this computation:
     * height(node) only ever depends on node's OWN children.
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
        List<Edge> ch = childrenOf.get(node);
        int h;
        if (ch == null || ch.isEmpty()) {
            h = 0;
        } else {
            int mx = 0;
            for (Edge c : ch) mx = Math.max(mx, computeHeightRec(c.otherId, height, visiting));
            h = mx + 1;
        }
        visiting.remove(node);
        height.put(node, h);
        return h;
    }

    /**
     * Computes EU(id, T) for every leaf AND every generalised ancestor
     * reachable from this ONE transaction, following the tiered, weighted
     * roll-up of Dinh nghia 3.3-3.4:
     *
     *   EU(leaf, T)  = u(leaf, T) x p(leaf, T)            (already computed by the caller)
     *   EU(g, T)     = sum [ EU(c, T) x weight(c -> g) ],  c in Children(g)
     *
     * computed strictly tier by tier (height 1, then height 2, ...) so that
     * every child's EU(c, T) is fully settled before any parent at the next
     * tier reads it -- required because with multi-parent edges a node's
     * children can straddle earlier tiers, unlike the old single-parent
     * "flatten the whole ancestor chain in one pass" shortcut, which is no
     * longer valid once more than one edge weight can appear along a path.
     * A parent with no child present in T (every child's EU(c,T) is 0 or
     * absent) is simply left out of the result, exactly as an absent leaf
     * item was left out of the old flat-sum model.
     *
     * @param leafIds  this transaction's leaf item ids
     * @param leafEU   EU(leaf, T) values parallel to {@code leafIds} (already probability-discounted)
     * @param height   precomputed heights, as returned by {@link #computeHeights}
     * @param maxLevel the highest height among all registered items
     * @return map from item id (leaf or generalised) to its EU(id, T) within this one transaction
     */
    public Map<Integer, Double> computeTransactionEU(int[] leafIds, double[] leafEU,
                                                       Map<Integer, Integer> height, int maxLevel) {
        Map<Integer, Double> euAt = new HashMap<>();
        for (int i = 0; i < leafIds.length; i++) {
            euAt.merge(leafIds[i], leafEU[i], Double::sum); // defensive: duplicate leaf id within one line
        }
        for (int lvl = 1; lvl <= maxLevel; lvl++) {
            for (Map.Entry<Integer, List<Edge>> e : childrenOf.entrySet()) {
                int parent = e.getKey();
                if (height.getOrDefault(parent, 0) != lvl) continue; // process one tier at a time
                double sum = 0;
                boolean any = false;
                for (Edge c : e.getValue()) {
                    Double childEU = euAt.get(c.otherId);
                    if (childEU == null || childEU == 0.0) continue;
                    sum += childEU * c.weight; // EU(g,T) += EU(c,T) x weight(c->g)   [Dinh nghia 3.4]
                    any = true;
                }
                if (any) euAt.merge(parent, sum, Double::sum);
            }
        }
        return euAt;
    }
}
