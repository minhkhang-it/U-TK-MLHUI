package mltko;

/**
 * An immutable snapshot of one {@link MlTKOAlgorithm} run's performance
 * counters, for one ablation variant. Instances are produced in {@link
 * Main} and consumed by {@link ResultWriter#writePerformance} and {@link
 * ResultWriter#writeAblationSummary}.
 */
public final class PerformanceMetrics {
    /** Name of the ablation variant this snapshot belongs to, e.g. {@code "mltko-nop"}. */
    public final String variantName;
    /** The Top-K parameter k used for this run. */
    public final int k;
    /** Display name of the dataset this run was executed on. */
    public final String datasetName;
    /** Wall-clock execution time of {@link MlTKOAlgorithm#run()}, in milliseconds. */
    public final long executionTimeMs;
    /** Peak JVM heap usage observed during the run, in megabytes. */
    public final double peakMemoryMB;
    /** Total number of DFS search-tree nodes visited (see {@link MlTKOAlgorithm#candidatesGenerated}). */
    public final long candidatesGenerated;
    /** Total number of {@code CONSTRUCT()} calls performed (see {@link MlTKOAlgorithm#scannedItemLists}). */
    public final long scannedItemLists;

    /**
     * Creates an immutable performance snapshot.
     *
     * @param variantName          name of the ablation variant
     * @param k                    the Top-K parameter used
     * @param datasetName          display name of the dataset
     * @param executionTimeMs      wall-clock execution time, in milliseconds
     * @param peakMemoryMB         peak JVM heap usage, in megabytes
     * @param candidatesGenerated  total DFS candidates visited
     * @param scannedItemLists     total {@code CONSTRUCT()} calls performed
     */
    public PerformanceMetrics(String variantName, int k, String datasetName,
                               long executionTimeMs, double peakMemoryMB,
                               long candidatesGenerated, long scannedItemLists) {
        this.variantName = variantName;
        this.k = k;
        this.datasetName = datasetName;
        this.executionTimeMs = executionTimeMs;
        this.peakMemoryMB = peakMemoryMB;
        this.candidatesGenerated = candidatesGenerated;
        this.scannedItemLists = scannedItemLists;
    }
}
