package mltko;

public final class PerformanceMetrics {
    public final String variantName;
    public final int k;
    public final String datasetName;
    public final long executionTimeMs;
    public final double peakMemoryMB;
    public final long candidatesGenerated;
    public final long scannedItemLists;

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
