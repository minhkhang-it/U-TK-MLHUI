package mltko;

/**
 * One transaction line parsed from a {@code utility.txt}-style file:
 * {@code item1 item2 ... : total_utility : util1 util2 ...}
 * Instances are immutable and produced exclusively by {@link DataLoader#loadAll}.
 */
public final class Transaction {
    /** Zero-based, file-order identifier of this transaction. */
    public final int tid;
    /** Leaf item ids present in this transaction, ascending as read from the file. */
    public final int[] items;
    /** Per-item utility values; {@code utils[i]} corresponds to {@code items[i]}. */
    public final long[] utils;
    /** The transaction's total utility TU(Tq), as given verbatim by the file's 2nd field. */
    public final long transactionUtility;

    /**
     * Creates an immutable transaction record.
     *
     * @param tid                 zero-based transaction id
     * @param items               leaf item ids present in the transaction
     * @param utils               per-item utility values, aligned index-for-index with {@code items}
     * @param transactionUtility  the transaction's total utility TU(Tq)
     */
    public Transaction(int tid, int[] items, long[] utils, long transactionUtility) {
        this.tid = tid;
        this.items = items;
        this.utils = utils;
        this.transactionUtility = transactionUtility;
    }
}
