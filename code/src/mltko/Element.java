package mltko;

/**
 * A single tuple in a Utility-List: <tid, iutil, rutil>
 *  - tid   : id of the transaction
 *  - iutil : u(X, Tq)  utility of the itemset/pattern X in transaction Tq
 *  - rutil : r(X, Tq)  remaining utility (sum of utilities of items that
 *            appear strictly after X, according to the TWU total order,
 *            inside the same transaction)
 */
public final class Element {
    public final int tid;
    public final long iutil;
    public final long rutil;

    public Element(int tid, long iutil, long rutil) {
        this.tid = tid;
        this.iutil = iutil;
        this.rutil = rutil;
    }
}
