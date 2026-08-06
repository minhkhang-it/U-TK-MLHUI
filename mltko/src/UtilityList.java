package mltko;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility-List of a pattern (single item or an itemset built along the
 * DFS search tree). Elements are always kept sorted ascending by tid,
 * which allows CONSTRUCT() to merge two lists with a linear two-pointer
 * scan instead of a nested loop.
 */
public final class UtilityList {

    /** the last item that was appended to build this pattern (used for EUCS / ancestor checks) */
    public final int item;

    public final List<Element> elements = new ArrayList<>();
    public long sumIutil = 0L;
    public long sumRutil = 0L;

    public UtilityList(int item) {
        this.item = item;
    }

    public void addElement(Element e) {
        elements.add(e);
        sumIutil += e.iutil;
        sumRutil += e.rutil;
    }
}
