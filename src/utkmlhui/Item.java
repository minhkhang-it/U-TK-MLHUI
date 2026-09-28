package utkmlhui;

import java.util.*;

/**
 * Represents an item in the multi-level taxonomy: either a specialised
 * (leaf) item or a generalised (internal taxonomy node) item.
 *
 * U-TK-MLHUI extension: every numeric quantity here is an EXPECTED
 * (probability-discounted) quantity, per the "E" prefix convention of the
 * spec's ky hieu table (u -> EU, TWU -> EGTWU):
 *   - egtwu           : EGTWU(id), the expected Generalised TWU, used only
 *                        for the global processing order (same role as the
 *                        original TWU-based order in TK-MLHUI).
 *   - expectedUtility : EU(id), the true expected utility of this single
 *                        item rolled up over the whole database (leaf: sum
 *                        of EU(leaf,T); generalised: sum of EU(leaf,T) over
 *                        all present descendants, Def. 4 mo rong / Muc 1.2).
 */
public class Item {
    /** Numeric identifier, shared by leaf items and generalised (taxonomy) nodes alike. */
    public int id;
    /** Display name, or {@code null} if this id never had one registered from {@code name.txt}. */
    public String name;
    /** Multi-level height in the taxonomy: 0 for a true leaf, increasing towards the root. */
    public int height;
    /** EGTWU(id): expected Generalised TWU, used only for the global processing order (mirrors TK-MLHUI's TWU-based order). */
    public double egtwu = 0;
    /** EU(id): expected utility, rolled up through the taxonomy (leaf: sum of EU(leaf,T); generalised: sum over present descendants, Def. 4 mo rong / Muc 1.2). */
    public double expectedUtility = 0;

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
