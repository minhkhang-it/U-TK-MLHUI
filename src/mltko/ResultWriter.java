package mltko;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes all output files (UTF-8), to disk only - nothing is echoed to
 * the console. Every method here corresponds to exactly one output file:
 *   {@link #writeTopKPatterns} - {@code output_topk_patterns_{variant}.txt}
 *   {@link #writePerformance} - {@code output_performance.txt}
 *   {@link #writeMuThresholdLog} - {@code output_mu_threshold_log_{variant}.txt}
 *   {@link #writeLevelDebug} - {@code output_level_debug.txt}
 *   {@link #writeAblationSummary} - {@code output_ablation_summary.txt}
 */
public final class ResultWriter {

    /** Not instantiable: {@link ResultWriter} is a static-only utility class. */
    private ResultWriter() {
    }

    /**
     * Writes {@code output_topk_patterns_{variant}.txt}: a
     * "{@code Dataset: ... | k = ...}" header line, then the header row
     * "{@code Rank\tUtility\tPattern (id:name)}", followed by one
     * tab-separated data row per pattern. Overwritten on every call (the
     * file reflects only the most recent run of that variant).
     *
     * @param path        output file path to write
     * @param datasetName display name of the dataset, for the header line
     * @param k           the Top-K parameter, for the header line
     * @param topK        the Top-K patterns to write, in the order they should be ranked
     * @param itemNames   map from item id to display name, used to render each pattern
     * @throws IOException if the file cannot be written
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
     * Writes/appends to {@code output_performance.txt}: a header row
     * "{@code Variant | k | Dataset | Execution Time | Peak Memory |
     * Candidates | Scanned Lists}" followed by one data row per supplied
     * variant. This file is APPENDED across runs (never overwritten) so
     * it accumulates a history of every run made so far; the header row
     * is written only once, the first time the file is created.
     *
     * @param path    output file path to append to
     * @param metrics one {@link PerformanceMetrics} entry per variant run this invocation
     * @throws IOException if the file cannot be written
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
     * Writes {@code output_mu_threshold_log_{variant}.txt}: the
     * dataset/k header, followed by one line per {@link MuThresholdLog.Entry}
     * (the initial {@code mu}, then every RUC raise) for one ablation
     * variant. Overwritten on every call.
     *
     * @param path        output file path to write
     * @param datasetName display name of the dataset, for the header line
     * @param k           the Top-K parameter, for the header line
     * @param log         the variant's recorded {@code mu} trajectory
     * @param itemNames   map from item id to display name, used to render triggering patterns
     * @throws IOException if the file cannot be written
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

    /**
     * Renders a raw item-id array as a verbose, brace-delimited string,
     * e.g. {@code "{110 (Fruits), 500 (AllGrocery)}"}, for the mu-log.
     *
     * @param items     item ids making up the pattern
     * @param itemNames map from item id to display name; ids missing from
     *                  this map are rendered with the id only
     * @return the verbose display representation, including surrounding braces
     */
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
     * Writes {@code output_level_debug.txt}: the dataset/k header,
     * followed by a table listing, for every category (internal /
     * generalized node) in the taxonomy, both level computations side by
     * side:
     *   {@link TaxonomyTree#levelOf} - longest path to a leaf (USED BY the algorithm)
     *   {@link TaxonomyTree#levelOfShortest} - shortest path to a leaf (Sec. 3.1 literal reading; kept only for comparison)
     * This report is variant-independent and is computed once per run.
     * Overwritten on every call.
     *
     * @param path        output file path to write
     * @param datasetName display name of the dataset, for the header line
     * @param k           the Top-K parameter, for the header line
     * @param taxonomy    the loaded taxonomy to report on
     * @param itemNames   map from item id to display name, used to label each category row
     * @throws IOException if the file cannot be written
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

    /**
     * Writes {@code output_ablation_summary.txt}: the ablation study
     * report (Section 5 of the paper, plus the bonus
     * {@code mltko-wo-merge} variant) - a side-by-side table of
     * candidates/scanned-lists/time per variant that was run this
     * invocation, followed by a CORRECTNESS check when 2 or more variants
     * were run.
     *
     * Turning EUCP or the merge optimisation off only removes a
     * pruning/dedup step - it must never change WHICH Top-K itemsets are
     * found, only how many candidates are visited to find them. When
     * {@code resultsByVariant} holds 2 or more entries, this method
     * verifies that every variant's Top-K itemset SET (ignoring
     * order/ties) is identical to the {@code baselineVariant}'s, and
     * reports any mismatch item-by-item (which would indicate a
     * pruning-correctness bug, not an expected ablation effect). When only
     * one variant was run, no comparison is possible and a one-line note
     * says so instead. Overwritten on every call.
     *
     * @param path              output file path to write
     * @param datasetName       display name of the dataset, for the header line
     * @param k                 the Top-K parameter, for the header line
     * @param baselineVariant   name of the variant used as the correctness-check baseline (typically {@code "mltko"}, or the single variant that was run)
     * @param resultsByVariant  each variant run this invocation's Top-K result list, keyed by variant name
     * @param metrics           each variant's performance snapshot, in the display order for the table
     * @param itemNames         map from item id to display name (currently unused by the rendered
     *                          table but reserved for future, more verbose reports)
     * @throws IOException if the file cannot be written
     */
    public static void writeAblationSummary(String path, String datasetName, int k, String baselineVariant,
                                             LinkedHashMap<String, List<Pattern>> resultsByVariant,
                                             List<PerformanceMetrics> metrics,
                                             Map<Integer, String> itemNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(datasetHeader(datasetName, k));
        if (resultsByVariant.size() > 1) {
            sb.append("Ablation study (Sec. 5): ").append(String.join(" vs. ", resultsByVariant.keySet())).append("\n");
        } else {
            sb.append("Ablation study: single variant run (").append(String.join("", resultsByVariant.keySet())).append(")\n");
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format("%-16s | %-10s | %-14s | %-16s | %-10s%n",
                "Variant", "#Patterns", "Candidates", "ScannedLists", "Time(ms)"));
        for (PerformanceMetrics m : metrics) {
            int count = resultsByVariant.getOrDefault(m.variantName, Collections.emptyList()).size();
            sb.append(String.format("%-16s | %-10d | %-14d | %-16d | %-10d%n",
                    m.variantName, count, m.candidatesGenerated, m.scannedItemLists, m.executionTimeMs));
        }
        sb.append("--------------------------------------------------------------------------------\n");

        if (resultsByVariant.size() < 2) {
            sb.append("Only one variant was run - no cross-variant correctness comparison to perform.\n");
            writeUtf8(path, sb.toString());
            return;
        }

        Set<String> baseSet = toItemsetKeySet(resultsByVariant.get(baselineVariant));
        boolean allMatch = true;
        for (Map.Entry<String, List<Pattern>> e : resultsByVariant.entrySet()) {
            if (e.getKey().equals(baselineVariant)) continue;
            Set<String> other = toItemsetKeySet(e.getValue());
            if (!other.equals(baseSet)) {
                allMatch = false;
                Set<String> onlyBase = new TreeSet<>(baseSet);
                onlyBase.removeAll(other);
                Set<String> onlyOther = new TreeSet<>(other);
                onlyOther.removeAll(baseSet);
                sb.append("MISMATCH vs. ").append(baselineVariant).append(" for ").append(e.getKey()).append(":\n");
                if (!onlyBase.isEmpty()) {
                    sb.append("  only in ").append(baselineVariant).append(": ").append(onlyBase).append("\n");
                }
                if (!onlyOther.isEmpty()) {
                    sb.append("  only in ").append(e.getKey()).append(": ").append(onlyOther).append("\n");
                }
            }
        }
        if (allMatch) {
            sb.append("OK: all variants found the exact same set of Top-K itemsets\n");
            sb.append("(pruning/merge switches only affected search cost, not correctness).\n");
        }
        writeUtf8(path, sb.toString());
    }

    /**
     * Builds the itemset-identity key set used by the ablation
     * correctness check: each pattern's sorted item-id array, rendered
     * as a string via {@link Arrays#toString(int[])}.
     *
     * @param patterns the patterns to key, or {@code null}
     * @return a set of string keys, one per distinct itemset; empty if {@code patterns} is {@code null}
     */
    private static Set<String> toItemsetKeySet(List<Pattern> patterns) {
        Set<String> set = new HashSet<>();
        if (patterns == null) return set;
        for (Pattern p : patterns) set.add(Arrays.toString(p.items));
        return set;
    }

    /**
     * Builds the shared "{@code Dataset: ... | k = ...}" header line
     * prepended to most output files.
     *
     * @param datasetName display name of the dataset
     * @param k           the Top-K parameter
     * @return the header line, including its trailing newline
     */
    private static String datasetHeader(String datasetName, int k) {
        return "Dataset: " + datasetName + " | k = " + k + "\n";
    }

    /**
     * Checks whether a file already exists and has content, to decide
     * whether {@link #writePerformance} needs to (re-)write its header row.
     *
     * @param path the file path to check
     * @return {@code true} if {@code path} names an existing, non-empty regular file
     */
    private static boolean fileExistsNonEmpty(String path) {
        try {
            return Files.isRegularFile(Paths.get(path)) && Files.size(Paths.get(path)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes (overwriting) UTF-8 text content to a file.
     *
     * @param path    destination file path
     * @param content the text to write
     * @throws IOException if the file cannot be written
     */
    private static void writeUtf8(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }

    /**
     * Appends (does not overwrite) UTF-8 text content to a file - used
     * for {@code output_performance.txt} so it accumulates run history.
     *
     * @param path    destination file path
     * @param content the text to append
     * @throws IOException if the file cannot be written
     */
    private static void writeUtf8Append(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
