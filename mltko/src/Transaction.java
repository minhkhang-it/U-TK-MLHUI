package mltko;

/**
 * One transaction line parsed from fruithut_utility.txt
 *   item1 item2 ... : total_utility : util1 util2 ...
 */
public final class Transaction {
    public final int tid;
    public final int[] items;      // leaf item ids, ascending as read from file
    public final long[] utils;     // utils[i] corresponds to items[i]
    public final long transactionUtility; // TU(Tq) as given in the file (2nd field)

    public Transaction(int tid, int[] items, long[] utils, long transactionUtility) {
        this.tid = tid;
        this.items = items;
        this.utils = utils;
        this.transactionUtility = transactionUtility;
    }
}
