package mltko;

/**
 * A single tuple in a {@link UtilityList}: {@code <tid, iutil, rutil>}.
 * Instances are immutable.
 */
public final class Element {
    /** Id of the transaction this element belongs to. */
    public final int tid;
    /** u(X, Tq): utility of the itemset/pattern X in transaction Tq. */
    public final long iutil;
    /**
     * r(X, Tq): remaining utility - the sum of utilities of items that
     * appear strictly after X, according to the TWU total order, inside
     * the same transaction.
     */
    public final long rutil;

    /**
     * Creates an immutable Utility-List element.
     *
     * @param tid   id of the owning transaction
     * @param iutil u(X, Tq), the itemset's utility in this transaction
     * @param rutil r(X, Tq), the remaining utility in this transaction
     */
    public Element(int tid, long iutil, long rutil) {
        this.tid = tid;
        this.iutil = iutil;
        this.rutil = rutil;
    }
}
