package mltko;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility-List of a pattern (a single item, or an itemset built along the
 * DFS search tree in {@link MlTKOAlgorithm#search}). Elements are always
 * kept sorted ascending by tid, which allows {@link
 * MlTKOAlgorithm#construct} to merge two lists with a linear two-pointer
 * scan instead of a nested loop.
 */
public final class UtilityList {

    /** The last item appended to build this pattern (used for EUCS lookups / ancestor checks). */
    public final int item;

    /** This pattern's elements, kept sorted ascending by {@link Element#tid}. */
    public final List<Element> elements = new ArrayList<>();
    /** Running sum of {@link Element#iutil} over all elements added so far. */
    public long sumIutil = 0L;
    /** Running sum of {@link Element#rutil} over all elements added so far. */
    public long sumRutil = 0L;

    /**
     * Creates an empty Utility-List for the given item.
     *
     * @param item the last item id appended to build this pattern
     */
    public UtilityList(int item) {
        this.item = item;
    }

    /**
     * Appends one element to this Utility-List (elements must be added in
     * ascending tid order to preserve the class invariant) and updates
     * the running {@link #sumIutil} / {@link #sumRutil} totals.
     *
     * @param e the element to append
     */
    public void addElement(Element e) {
        elements.add(e);
        sumIutil += e.iutil;
        sumRutil += e.rutil;
    }
}
