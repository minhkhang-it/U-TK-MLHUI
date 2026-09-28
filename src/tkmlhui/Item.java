package tkmlhui;

import java.util.*;

/**
 * Represents an item in the multi-level taxonomy: either a specialised
 * (leaf) item or a generalised (internal taxonomy node) item.
 */
public class Item {
    /** Numeric identifier, shared by leaf items and generalised (taxonomy) nodes alike. */
    public int id;
    /** Display name, or {@code null} if this id never had one registered from {@code name.txt}. */
    public String name;
    /** Multi-level height in the taxonomy: 0 for a true leaf, increasing towards the root. */
    public int height;
    /** GTWU(id): (multi-level, generalised) transaction-weighted utility, used only for the global processing order. */
    public long twu = 0;
    /** u(id): real utility, rolled up through the taxonomy (leaf: sum of u(id,T); generalised: sum over present descendants). */
    public long exactUtility = 0;

    /**
     * Creates an item record.
     *
     * @param id   numeric identifier (leaf or generalised taxonomy node)
     * @param name display name, or {@code null} if not yet known
     */
    public Item(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
