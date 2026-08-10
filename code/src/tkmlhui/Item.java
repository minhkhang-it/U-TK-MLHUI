package tkmlhui;

import java.util.*;

/**
 * Represents an item in the multi-level taxonomy: either a specialised
 * (leaf) item or a generalised (internal taxonomy node) item.
 */
public class Item {
    public int id;
    public String name;
    public int height;          // 0 = leaf; increases towards the root
    public long twu = 0;        // (multi-level) transaction-weighted utility, used for ordering
    public long exactUtility = 0; // real utility, rolled up through the taxonomy

    public Item(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
