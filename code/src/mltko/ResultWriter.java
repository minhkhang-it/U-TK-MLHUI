package mltko;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the output files (UTF-8), to disk only (no console echo):
 *   1. output_topk_patterns.txt     -- Top-K high utility patterns (mlTKO)
 *   2. output_performance.txt       -- performance metrics for the mlTKO run
 *   3. output_mu_threshold_log.txt  -- mu (minimum-utility) trajectory
 *   4. output_level_debug.txt       -- taxonomy level debug info
 */
public final class ResultWriter {

    /**
     * output_topk_patterns.txt
     * Format: a "Dataset: ... | k = ..." header line, then the header row
     * "Rank\tUtility\tPattern (id:name)", followed by one tab-separated
     * data row per pattern. Overwritten each run (this file reflects only
     * the most recent run).
     */
    public static void writeTopKPatterns(String path, String datasetName, int k, List<Pattern> topK,
                                          Map<Integer, String> itemNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(datasetHeader(datasetName, k));
        sb.append("Rank").append('\t').append("Utility").append('\t').append("Pattern (id:name)").append('\n');
        int rank = 1;
        for (Pattern p : topK) {
            sb.append(rank++).append('\t')
              .append(p.utility).append('\t')
              .append('{').append(p.toIdNameString(itemNames)).append('}')
              .append('\n');
        }
        if (topK.isEmpty()) {
            sb.append("(no pattern found)\n");
        }
        writeUtf8(path, sb.toString());
    }

    /**
     * output_performance.txt
     * Format: header row "Variant | k | Dataset | Execution Time | Peak Memory | Candidates | Scanned Lists"
     * followed by one data row per variant that was run. This file is
     * APPENDED across runs (never overwritten) so it accumulates a history
     * of every run made so far; the header row is written only once, the
     * first time the file is created.
     */
    public static void writePerformance(String path, List<PerformanceMetrics> metrics) throws IOException {
        boolean needsHeader = !fileExistsNonEmpty(path);
        StringBuilder sb = new StringBuilder();
        if (needsHeader) {
            sb.append(String.format("%-20s | %-6s | %-20s | %-18s | %-14s | %-12s | %-16s%n",
                    "Variant", "k", "Dataset", "Execution Time", "Peak Memory", "Candidates", "Scanned Lists"));
        }
        for (PerformanceMetrics m : metrics) {
            String execTime = m.executionTimeMs + " ms";
            String peakMem = String.format("%.2f MB", m.peakMemoryMB);
            sb.append(String.format("%-20s | %-6d | %-20s | %-18s | %-14s | %-12d | %-16d%n",
                    m.variantName, m.k, m.datasetName, execTime, peakMem,
                    m.candidatesGenerated, m.scannedItemLists));
        }
        writeUtf8Append(path, sb.toString());
    }

    /**
     * output_mu_threshold_log.txt -- prepended with the dataset/k header
     * that applies to every "remaining" (non-tabular-spec) output file.
     */
    public static void writeMuThresholdLog(String path, String datasetName, int k, MuThresholdLog log,
                                            Map<Integer, String> itemNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(datasetHeader(datasetName, k));
        for (MuThresholdLog.Entry e : log.entries()) {
            if (e.triggeringPattern == null) {
                sb.append(String.format(
                        "INIT | candidates=%d | minU: (none) -> %d | trigger=%s%n",
                        e.candidateIndex, e.newMu, e.reason));
            } else {
                sb.append(String.format(
                        "candidates=%d | minU: %d -> %d | trigger=%s%n",
                        e.candidateIndex, e.oldMu, e.newMu, patternToString(e.triggeringPattern, itemNames)));
            }
        }
        if (log.entries().isEmpty()) {
            sb.append("(no threshold raise recorded)\n");
        }
        writeUtf8(path, sb.toString());
    }

    private static String patternToString(int[] items, Map<Integer, String> itemNames) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.length; i++) {
            int id = items[i];
            String name = itemNames.get(id);
            sb.append(id);
            if (name != null) sb.append(" (").append(name).append(")");
            if (i < items.length - 1) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * output_level_debug.txt -- prepended with the dataset/k header. Prints,
     * for every category (internal/generalized node) in the taxonomy, both
     * level computations side by side:
     *   - levelOf()         : longest path to a leaf  (USED BY the algorithm)
     *   - levelOfShortest() : shortest path to a leaf (Sec. 3.1 literal
     *                         reading; kept only for comparison)
     */
    public static void writeLevelDebug(String path, String datasetName, int k, TaxonomyTree taxonomy,
                                        Map<Integer, String> itemNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(datasetHeader(datasetName, k));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("LEVEL DEBUG -- longest-path (levelOf, USED BY ALGORITHM) vs shortest-path (levelOfShortest, reference only)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format("%-10s | %-24s | %-10s | %-10s | %-9s%n",
                "Item id", "Name", "level(used)", "level(shortest-ref)", "#children"));
        sb.append("---------------------------------------------------------------------------------------\n");

        Map<Integer, Integer> memoUsed = new HashMap<>();
        Map<Integer, Integer> memoShortest = new HashMap<>();
        List<Integer> categories = new ArrayList<>(taxonomy.allInternalNodes());
        Collections.sort(categories);

        Map<Integer, Integer> countUsed = new java.util.TreeMap<>();
        Map<Integer, Integer> countShortest = new java.util.TreeMap<>();

        for (int id : categories) {
            int lvlUsed = taxonomy.levelOf(id, memoUsed);
            int lvlShortest = taxonomy.levelOfShortest(id, memoShortest);
            String name = itemNames.getOrDefault(id, "");
            sb.append(String.format("%-10d | %-24s | %-10d | %-10d | %-9d%n",
                    id, name, lvlUsed, lvlShortest, taxonomy.childrenOf(id).size()));
            countUsed.merge(lvlUsed, 1, Integer::sum);
            countShortest.merge(lvlShortest, 1, Integer::sum);
        }

        sb.append("---------------------------------------------------------------------------------------\n");
        sb.append("Total categories: ").append(categories.size()).append("\n");
        sb.append("Distinct level(used, longest-path) values     : ").append(countUsed.keySet())
          .append("  | per-level counts: ").append(countUsed).append("\n");
        sb.append("Distinct level(shortest-ref) values            : ").append(countShortest.keySet())
          .append("  | per-level counts: ").append(countShortest).append("\n");
        sb.append("(The 'used' column should reproduce the dataset's documented\n");
        sb.append(" number of levels/categories.)\n");
        sb.append("================================================================================\n");

        writeUtf8(path, sb.toString());
    }

    private static String datasetHeader(String datasetName, int k) {
        return "Dataset: " + datasetName + " | k = " + k + "\n";
    }

    private static boolean fileExistsNonEmpty(String path) {
        try {
            return Files.isRegularFile(Paths.get(path)) && Files.size(Paths.get(path)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeUtf8(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }

    /** Appends (does not overwrite) -- used for output_performance.txt so it accumulates run history. */
    private static void writeUtf8Append(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
