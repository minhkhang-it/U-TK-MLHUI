package tkmlhui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Entry point.
 *
 * Usage:
 *   java Main <dataset_dir> [output_dir] [k] [--debug] [variant]
 *
 * dataset_dir must contain: name.txt, taxonomy.txt, utility.txt, and
 * optionally k.txt. If k is not given on the command line, it is read
 * from k.txt inside dataset_dir; if neither is present, k defaults to 10.
 *
 * variant selects which of the 3 paper ablation variants to run (matched
 * case-insensitively, "-"/"_" interchangeable, can appear anywhere in the
 * arguments): full (default) | wo-all | wo-merge | all
 *   java Main input/sample output 10 full
 *   java Main input/sample output 10 wo-all
 *   java Main input/sample output 10 wo-merge
 *   java Main input/sample output 10 all        (runs all 3, one after another,
 *                                                 in this single invocation)
 *
 * --debug (can appear anywhere in the arguments) additionally writes
 * output_debug_trace.txt with a full step-by-step trace: item heights,
 * global TWU order, the Strategy 1&2 pool and initial minU, and for every
 * level and every recursive Search() call: Sec/Pri contents, lu/su values,
 * every candidate itemset's utility, and every minU raise.
 *
 * Output files (all written into output_dir):
 *   - output_topk_patterns_tkmlhui_<variant>.txt : one per variant (all 3 write here)
 *   - output_performance.txt             : shared across variants (rows
 *                                          appended, never overwritten), so
 *                                          you can run all 3 and compare
 *   - output_mu_threshold_log_tkmlhui.txt : only written by the full variant
 *   - output_debug_trace.txt             : only with --debug
 */
public class Main {

    /**
     * Parses CLI arguments, resolves which variant(s) to run, and drives
     * one {@link #runOneVariant} call per selected variant.
     *
     * @param args the raw command-line arguments (see the class-level Usage section)
     * @throws IOException if reading the dataset files or writing an output file fails
     */
    public static void main(String[] args) throws IOException {
        List<String> positional = new ArrayList<>();
        boolean debugFlag = false;
        boolean runAll = false;
        TKMLHUIAlgo.Variant variant = TKMLHUIAlgo.Variant.FULL;
        for (String a : args) {
            if (a.equals("--debug")) {
                debugFlag = true;
                continue;
            }
            if (a.trim().equalsIgnoreCase("all")) {
                runAll = true;
                continue;
            }
            TKMLHUIAlgo.Variant v = TKMLHUIAlgo.Variant.fromArg(a);
            if (v != null) {
                variant = v;
                continue;
            }
            positional.add(a);
        }

        String datasetDir = positional.size() > 0 ? positional.get(0) : "input/sample";
        String outputDir = positional.size() > 1 ? positional.get(1) : "output";
        Integer kArg = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : null;

        Files.createDirectories(Paths.get(outputDir));

        List<TKMLHUIAlgo.Variant> toRun = runAll
                ? Arrays.asList(TKMLHUIAlgo.Variant.FULL, TKMLHUIAlgo.Variant.WO_MERGE, TKMLHUIAlgo.Variant.WO_ALL)
                : Collections.singletonList(variant);

        for (TKMLHUIAlgo.Variant v : toRun) {
            runOneVariant(v, datasetDir, outputDir, kArg, debugFlag);
        }
    }

    /**
     * Loads the dataset fresh, runs one ablation variant end to end, and
     * writes its output files.
     *
     * @param variant    the ablation variant to run
     * @param datasetDir path to the dataset folder (must contain {@code taxonomy.txt} and {@code utility.txt})
     * @param outputDir  path to the output folder (created if missing)
     * @param kArg       explicit top-k value from the CLI, or {@code null} to fall back to {@code k.txt} / the default
     * @param debugFlag  whether to write a full step-by-step debug trace
     * @throws IOException if reading the dataset files or writing an output file fails
     */
    private static void runOneVariant(TKMLHUIAlgo.Variant variant, String datasetDir, String outputDir,
                                       Integer kArg, boolean debugFlag) throws IOException {
        // Each variant gets a fresh Taxonomy/Algo instance -- input is small enough that
        // reloading per variant is cheap, and it avoids any risk of state (heap, minU,
        // logs, counters) leaking between runs when using `all`.
        Taxonomy taxonomy = new Taxonomy();
        TKMLHUIAlgo algo = new TKMLHUIAlgo(taxonomy);
        algo.variant = variant;
        algo.debug = debugFlag;

        Map<Integer, String> names = loadNames(Paths.get(datasetDir, "name.txt"));
        loadTaxonomy(Paths.get(datasetDir, "taxonomy.txt"), taxonomy);
        for (Map.Entry<Integer, String> e : names.entrySet()) {
            algo.registerItem(e.getKey(), e.getValue());
        }

        loadUtility(Paths.get(datasetDir, "utility.txt"), algo);

        int k = kArg != null ? kArg : loadK(Paths.get(datasetDir, "k.txt"), 10);
        algo.setK(k);

        System.out.println("Running " + variant.label + " on dataset: " + datasetDir + "  (k=" + k + ")");
        List<TKMLHUIAlgo.Pattern> results = algo.run();

        // Use the dataset folder's own name (not the full/relative path passed on the
        // command line) in every output file, so it lines up with mlTKO's convention
        // and the two algorithms' output_performance.txt rows show the same Dataset
        // value for the same dataset (e.g. "toy", not "toy" vs "input/toy").
        String datasetName = datasetNameOf(datasetDir);

        String variantSuffix = variant.name().toLowerCase(); // full | wo_all | wo_merge
        writeTopK(Paths.get(outputDir, "output_topk_patterns_tkmlhui_" + variantSuffix + ".txt"), algo, results, datasetName, k);
        writePerformance(Paths.get(outputDir, "output_performance.txt"), algo, k, datasetName, variant);
        if (variant == TKMLHUIAlgo.Variant.FULL) {
            writeMuLog(Paths.get(outputDir, "output_mu_threshold_log_tkmlhui.txt"), algo, datasetName, k);
        }
        if (debugFlag) {
            writeDebugTrace(Paths.get(outputDir, "output_debug_trace_" + variantSuffix + ".txt"), algo);
        }

        System.out.println("Done. " + results.size() + " pattern(s) found. Output written to: " + outputDir);
    }

    /**
     * Best-effort human-readable dataset name for headers: the folder's own name (mirrors mltko.Main).
     *
     * @param dataFolder path to the dataset folder
     * @return the folder's file name, or {@code dataFolder} itself if it has no name component
     */
    private static String datasetNameOf(String dataFolder) {
        Path p = Paths.get(dataFolder).getFileName();
        return p != null ? p.toString() : dataFolder;
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    /**
     * Parses {@code name.txt} ({@code @ITEM=id=ten_item} lines) into an id -> display-name map.
     *
     * @param path path to {@code name.txt}
     * @return the id -> display-name map, empty if the file does not exist
     * @throws IOException if the file exists but cannot be read
     */
    private static Map<Integer, String> loadNames(Path path) throws IOException {
        Map<Integer, String> map = new HashMap<>();
        if (!Files.exists(path)) return map;
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // format: @ITEM=id=ten_item
            String body = line.startsWith("@ITEM=") ? line.substring("@ITEM=".length()) : line;
            int sep = body.indexOf('=');
            if (sep < 0) continue;
            try {
                int id = Integer.parseInt(body.substring(0, sep).trim());
                String name = body.substring(sep + 1).trim();
                map.put(id, name);
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    /**
     * Parses {@code taxonomy.txt} ({@code child_id,parent_id} lines) into edges registered on {@code taxonomy}.
     *
     * @param path     path to {@code taxonomy.txt}
     * @param taxonomy the {@link Taxonomy} to populate; a no-op if the file does not exist
     * @throws IOException if the file exists but cannot be read
     */
    private static void loadTaxonomy(Path path, Taxonomy taxonomy) throws IOException {
        if (!Files.exists(path)) return;
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 2) continue;
            int child = Integer.parseInt(parts[0].trim());
            int parent = Integer.parseInt(parts[1].trim());
            taxonomy.addEdge(child, parent);
        }
    }

    /**
     * Parses {@code utility.txt} ({@code items : TWU : utilities} lines) and registers each line as a transaction.
     *
     * @param path path to {@code utility.txt}
     * @param algo the {@link TKMLHUIAlgo} instance to register transactions into
     * @throws IOException if the file cannot be read
     */
    private static void loadUtility(Path path, TKMLHUIAlgo algo) throws IOException {
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(":");
            if (parts.length < 3) continue;
            String[] itemTok = parts[0].trim().split("\\s+");
            String[] utilTok = parts[2].trim().split("\\s+");
            int n = itemTok.length;
            int[] itemIds = new int[n];
            long[] utils = new long[n];
            for (int i = 0; i < n; i++) {
                itemIds[i] = Integer.parseInt(itemTok[i]);
                utils[i] = Long.parseLong(utilTok[i]);
            }
            algo.addTransaction(itemIds, utils);
        }
    }

    /**
     * Reads the top-k value from a {@code k.txt}-style file (first integer found in its content).
     *
     * @param path     path to the k-file
     * @param defaultK value to return if the file does not exist or contains no integer
     * @return the parsed k value, or {@code defaultK}
     */
    private static int loadK(Path path, int defaultK) {
        if (!Files.exists(path)) return defaultK;
        try {
            String content = new String(Files.readAllBytes(path));
            Matcher m = Pattern.compile("\\d+").matcher(content);
            if (m.find()) return Integer.parseInt(m.group());
        } catch (IOException ignored) {
        }
        return defaultK;
    }

    // ---------------------------------------------------------------------
    // Output writers
    // ---------------------------------------------------------------------

    /**
     * Writes the ranked top-k patterns (rank, utility, and item names) to a text file.
     *
     * @param path       output file path
     * @param algo       the algorithm instance (used to resolve item display names)
     * @param results    the top-k patterns, already sorted by descending utility
     * @param datasetDir the dataset name shown in the file header
     * @param k          the top-k parameter, shown in the file header
     * @throws IOException if the file cannot be written
     */
    private static void writeTopK(Path path, TKMLHUIAlgo algo, List<TKMLHUIAlgo.Pattern> results,
                                   String datasetDir, int k) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("Dataset: " + datasetDir + " | k = " + k);
            w.newLine();
            w.write("Rank\tUtility\tPattern (id:name)");
            w.newLine();
            int rank = 1;
            for (TKMLHUIAlgo.Pattern p : results) {
                StringBuilder sb = new StringBuilder();
                for (int id : p.itemIds) {
                    if (sb.length() > 0) sb.append(", ");
                    Item it = algo.getItem(id);
                    String name = (it != null && it.name != null) ? it.name : String.valueOf(id);
                    sb.append(id).append(":").append(name);
                }
                w.write(rank + "\t" + p.utility + "\t{" + sb + "}");
                w.newLine();
                rank++;
            }
            if (results.isEmpty()) {
                w.write("(no pattern found)");
                w.newLine();
            }
        }
    }

    /**
     * Appends one row per run so that running all 3 variants (in any order,
     * across separate invocations) accumulates into a single side-by-side
     * comparison table instead of each run overwriting the last one's result.
     */
    /**
     * Appends one performance row (variant, k, dataset, timing, memory, and scan counters) to the shared output file.
     *
     * @param path       output file path (created with a header row if it does not yet exist)
     * @param algo       the algorithm instance the metrics are read from
     * @param k          the top-k parameter for this run
     * @param datasetDir the dataset name for this row
     * @param variant    the ablation variant that produced this run
     * @throws IOException if the file cannot be written
     */
    private static void writePerformance(Path path, TKMLHUIAlgo algo, int k, String datasetDir,
                                          TKMLHUIAlgo.Variant variant) throws IOException {
        boolean exists = Files.exists(path);
        try (BufferedWriter w = Files.newBufferedWriter(path,
                exists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
            if (!exists) {
                w.write(String.format("%-20s | %-6s | %-20s | %-18s | %-14s | %-12s | %-16s",
                        "Variant", "k", "Dataset", "Execution Time", "Peak Memory", "Candidates", "Scanned Lists"));
                w.newLine();
            }
            w.write(String.format("%-20s | %-6d | %-20s | %-18s | %-14s | %-12d | %-16d",
                    variant.label,
                    k,
                    datasetDir,
                    algo.executionTimeMs + " ms",
                    String.format("%.2f MB", algo.peakMemoryMB),
                    algo.candidateCount,
                    algo.scannedListsCount));
            w.newLine();
        }
    }

    /**
     * Writes the algorithm's full step-by-step debug trace to a text file.
     *
     * @param path output file path
     * @param algo the algorithm instance whose {@code debugLog} to dump
     * @throws IOException if the file cannot be written
     */
    private static void writeDebugTrace(Path path, TKMLHUIAlgo algo) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            for (String line : algo.debugLog) {
                w.write(line);
                w.newLine();
            }
        }
    }

    /**
     * Writes the minU threshold trajectory (initial value plus every Strategy-3 raise) to a text file.
     *
     * @param path       output file path
     * @param algo       the algorithm instance whose {@code muThresholdLog} to dump
     * @param datasetDir the dataset name shown in the file header
     * @param k          the top-k parameter, shown in the file header
     * @throws IOException if the file cannot be written
     */
    private static void writeMuLog(Path path, TKMLHUIAlgo algo, String datasetDir, int k) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("Dataset: " + datasetDir + " | k = " + k);
            w.newLine();
            for (String line : algo.muThresholdLog) {
                w.write(line);
                w.newLine();
            }
            if (algo.muThresholdLog.isEmpty()) {
                w.write("(no threshold raise recorded)");
                w.newLine();
            }
        }
    }
}