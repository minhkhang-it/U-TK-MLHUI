package utkmlhui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Entry point for U-TK-MLHUI.
 *
 * Usage:
 *   java Main <dataset_dir> [output_dir] [k] [--debug] [variant] [prob_file.txt]
 *
 * dataset_dir must contain: name.txt, taxonomy.txt, and EITHER
 * utility_probability.txt (new pipeline format, preferred) OR the older
 * utility.txt + a probability file pair; k.txt is optional. If k is not
 * given on the command line, it is read from k.txt inside dataset_dir; if
 * neither is present, k defaults to 10.
 *
 * taxonomy.txt : {@code child_id,parent_id,weight} per line (Dinh nghia 3.1)
 *                -- a WEIGHTED, MULTI-PARENT DAG: the same child_id may
 *                appear on several lines, once per direct parent it has, at
 *                either tier (leaf->variant, variant->lemma). A 2-column
 *                line (no weight) is still accepted and defaults to
 *                weight=1.0, for backward compatibility with single-parent
 *                datasets.
 *
 * utility_probability.txt (preferred, new pipeline format -- see
 *                TOM_TAT_QUY_TRINH.md): ONE file, one line per transaction,
 *                leaf items only:
 *                    items : TWU : utilities : probabilities
 *                e.g. "1 2 3 : 297.16 : 83.27 120.97 92.92 : 1.0 1.0 1.0"
 *                Used automatically whenever it exists in dataset_dir (see
 *                loadUtilityProbabilityCombined()).
 *
 * utility.txt + probability file (older format, used when
 *                utility_probability.txt is absent): utility.txt is SAME
 *                format as TK-MLHUI -- items : TWU : utilities (the TWU
 *                field is informational only, never read by the algorithm
 *                -- EGTWU is always recomputed from EU). The probability
 *                file has ONE line per transaction, SAME order and SAME
 *                item list as the matching utility.txt line:
 *                    items : probabilities
 *                e.g. "1 2 3 5 : 0.69 0.51 0.58 0.57"
 *                prob_file.txt selects WHICH probability file inside
 *                dataset_dir to use. Any argument ending in ".txt" is taken
 *                as this file name -- it does not have to be exactly
 *                "probability.txt", and it can appear anywhere on the
 *                command line. If omitted, defaults to "probability.txt".
 *                  java Main input/sample output 10 full probability_alt.txt
 *
 * Either way, every probability must be in (0,1] (existential probability,
 * Dinh nghia 3.2) -- loading fails fast with a clear error otherwise. In
 * the current linguistic dataset this column is always 1.0 (all
 * uncertainty now lives in taxonomy.txt's edge weights instead). EU(i,T) =
 * u(i,T) x p(i,T) is computed once here, at load time, and is what gets fed
 * into UTKMLHUIAlgo.addTransaction() (Dinh nghia 3.3); generalised items'
 * EU is then rolled up tier by tier through the weighted DAG (Dinh nghia
 * 3.4, see Taxonomy.computeTransactionEU).
 *
 * variant selects which of the 5 spec ablation baselines (Muc 5.1) to run
 * (matched case-insensitively, "-"/"_" interchangeable, can appear
 * anywhere in the arguments):
 *   full (default) | wo-elu | wo-esu | wo-merge | wo-threshold-raising | all
 *   java Main input/sample output 10 full
 *   java Main input/sample output 10 wo-elu
 *   java Main input/sample output 10 wo-esu
 *   java Main input/sample output 10 wo-merge
 *   java Main input/sample output 10 wo-threshold-raising
 *   java Main input/sample output 10 all        (runs all 5, one after another,
 *                                                 in this single invocation)
 *
 * --debug (can appear anywhere in the arguments) additionally writes
 * output_debug_trace_<variant>.txt with a full step-by-step trace: item
 * heights, global EGTWU order, the Strategy 1&2 pool and initial minU, and
 * for every level and every recursive Search() call: Sec/Pri contents,
 * Elu/Esu values, every candidate itemset's EU, and every minU raise.
 *
 * --min-size N (default 2, can appear anywhere in the arguments) sets the
 * minimum itemset size accepted into the reported top-k. Default 2 because
 * for this linguistic uncertain dataset a size-1 "pattern" is just a single
 * leaf/variant/lemma translation on its own, not a meaningful co-occurrence
 * pattern (see UTKMLHUIAlgo.minPatternSize for why this is enforced during
 * the search itself, not by filtering the final list). Pass "--min-size 1"
 * to restore the unrestricted original behaviour.
 *
 * Output files (all written into output_dir):
 *   - output_topk_patterns_utkmlhui_<variant>.txt : one per variant (all 5 write here)
 *   - output_performance.txt              : SHARED with TK-MLHUI/mlTKO (rows
 *                                            appended, never overwritten), so
 *                                            all algorithms compare side by side.
 *                                            Column layout is UNCHANGED (no new
 *                                            column) -- if prob_file.txt is not
 *                                            the default "probability.txt", its
 *                                            name is appended to the Dataset
 *                                            column instead, e.g.
 *                                            "toy [probability_low_seed1.txt]".
 *   - output_mu_threshold_log_utkmlhui.txt : only written by the full variant
 *   - output_debug_trace_<variant>.txt     : only with --debug
 */
public class Main {

    /**
     * Parses CLI arguments, resolves which variant(s) to run and which
     * probability file to use, and drives one {@link #runOneVariant} call
     * per selected variant.
     *
     * @param args the raw command-line arguments (see the class-level Usage section)
     * @throws IOException if reading the dataset files or writing an output file fails
     */
    public static void main(String[] args) throws IOException {
        List<String> positional = new ArrayList<>();
        boolean debugFlag = false;
        boolean runAll = false;
        UTKMLHUIAlgo.Variant variant = UTKMLHUIAlgo.Variant.FULL;
        String probFileName = "probability.txt";
        int minPatternSize = 2;
        for (int ai = 0; ai < args.length; ai++) {
            String a = args[ai];
            if (a.equals("--debug")) {
                debugFlag = true;
                continue;
            }
            if (a.equals("--min-size") && ai + 1 < args.length) {
                minPatternSize = Integer.parseInt(args[++ai].trim());
                continue;
            }
            if (a.trim().equalsIgnoreCase("all")) {
                runAll = true;
                continue;
            }
            if (a.trim().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                probFileName = a.trim();
                continue;
            }
            UTKMLHUIAlgo.Variant v = UTKMLHUIAlgo.Variant.fromArg(a);
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

        List<UTKMLHUIAlgo.Variant> toRun = runAll
                ? Arrays.asList(UTKMLHUIAlgo.Variant.FULL, UTKMLHUIAlgo.Variant.WO_ELU, UTKMLHUIAlgo.Variant.WO_ESU,
                                 UTKMLHUIAlgo.Variant.WO_MERGE, UTKMLHUIAlgo.Variant.WO_THRESHOLD_RAISING)
                : Collections.singletonList(variant);

        for (UTKMLHUIAlgo.Variant v : toRun) {
            runOneVariant(v, datasetDir, outputDir, kArg, debugFlag, probFileName, minPatternSize);
        }
    }

    /**
     * Loads the dataset fresh, runs one ablation variant end to end, and
     * writes its output files.
     *
     * @param variant     the ablation variant to run
     * @param datasetDir  path to the dataset folder (must contain {@code taxonomy.txt}, {@code utility.txt}, and the probability file)
     * @param outputDir   path to the output folder (created if missing)
     * @param kArg        explicit top-k value from the CLI, or {@code null} to fall back to {@code k.txt} / the default
     * @param debugFlag   whether to write a full step-by-step debug trace
     * @param probFileName name of the probability file to use, resolved inside {@code datasetDir}
     * @param minPatternSize minimum itemset size accepted into the reported top-k (default 2; see {@link UTKMLHUIAlgo#minPatternSize})
     * @throws IOException if reading the dataset files or writing an output file fails
     */
    private static void runOneVariant(UTKMLHUIAlgo.Variant variant, String datasetDir, String outputDir,
                                       Integer kArg, boolean debugFlag, String probFileName, int minPatternSize) throws IOException {
        // Each variant gets a fresh Taxonomy/Algo instance -- input is small enough that
        // reloading per variant is cheap, and it avoids any risk of state (heap, minU,
        // logs, counters) leaking between runs when using `all`.
        Taxonomy taxonomy = new Taxonomy();
        UTKMLHUIAlgo algo = new UTKMLHUIAlgo(taxonomy);
        algo.variant = variant;
        algo.debug = debugFlag;
        algo.minPatternSize = minPatternSize;

        Map<Integer, String> names = loadNames(Paths.get(datasetDir, "name.txt"));
        loadTaxonomy(Paths.get(datasetDir, "taxonomy.txt"), taxonomy);
        for (Map.Entry<Integer, String> e : names.entrySet()) {
            algo.registerItem(e.getKey(), e.getValue());
        }

        Path combinedPath = Paths.get(datasetDir, "utility_probability.txt");
        if (Files.exists(combinedPath)) {
            // New pipeline output (TOM_TAT_QUY_TRINH.md): ONE file, leaf-level only,
            // "items : TWU : utilities : probabilities" -- takes priority over the
            // older separate utility.txt + probability.txt pair when both are present.
            loadUtilityProbabilityCombined(combinedPath, algo);
        } else {
            loadUtilityAndProbability(Paths.get(datasetDir, "utility.txt"), Paths.get(datasetDir, probFileName), algo);
        }

        int k = kArg != null ? kArg : loadK(Paths.get(datasetDir, "k.txt"), 10);
        algo.setK(k);

        System.out.println("Running " + variant.label + " on dataset: " + datasetDir + "  (k=" + k + ")");
        List<UTKMLHUIAlgo.Pattern> results = algo.run();

        // Use the dataset folder's own name (not the full/relative path passed on the
        // command line) in every output file, so it lines up with mlTKO/TK-MLHUI's
        // convention and every algorithm's output_performance.txt rows show the same
        // Dataset value for the same dataset (e.g. "toy", not "toy" vs "input/toy").
        String datasetName = datasetNameOf(datasetDir);

        // output_performance.txt is shared with mlTKO/TK-MLHUI and its column layout
        // must stay fixed (old rows have no "which prob file" column). So instead of
        // adding a new column, the non-default probability file name is folded into
        // the Dataset value itself, e.g. "toy [probability_low_seed1.txt]", leaving
        // the default "probability.txt" case looking exactly as before.
        String datasetForPerf = "probability.txt".equals(probFileName)
                ? datasetName
                : datasetName + " [" + probFileName + "]";

        String variantSuffix = variant.name().toLowerCase(); // full | wo_elu | wo_esu | wo_merge | wo_threshold_raising
        writeTopK(Paths.get(outputDir, "output_topk_patterns_utkmlhui_" + variantSuffix + ".txt"), algo, results, datasetName, k);
        writePerformance(Paths.get(outputDir, "output_performance.txt"), algo, k, datasetForPerf, variant);
        if (variant == UTKMLHUIAlgo.Variant.FULL) {
            writeMuLog(Paths.get(outputDir, "output_mu_threshold_log_utkmlhui.txt"), algo, datasetName, k);
        }
        if (debugFlag) {
            writeDebugTrace(Paths.get(outputDir, "output_debug_trace_" + variantSuffix + ".txt"), algo);
        }

        System.out.println("Done. " + results.size() + " pattern(s) found. Output written to: " + outputDir);
    }

    /**
     * Best-effort human-readable dataset name for headers: the folder's own name (mirrors mltko/tkmlhui.Main).
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
     * Parses {@code taxonomy.txt} ({@code child_id,parent_id,weight} lines,
     * Dinh nghia 3.1) into weighted edges registered on {@code taxonomy}.
     * The taxonomy is a multi-parent DAG: the SAME {@code child_id} may
     * appear on several lines (once per parent it has) -- this is the
     * intended encoding of multi-parent-ness, not a duplicate/error, and
     * every such line is registered as its own edge.
     *
     * A 2-column line ({@code child_id,parent_id}, no weight) is still
     * accepted for backward compatibility with older single-parent
     * datasets and defaults its weight to 1.0.
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
            double weight = parts.length >= 3 ? Double.parseDouble(parts[2].trim()) : 1.0;
            if (!(weight > 0.0 && weight <= 1.0)) {
                throw new IllegalArgumentException(String.format(
                        "Invalid taxonomy weight in %s: weight(%d -> %d) = %s is not in (0, 1]. (Dinh nghia 3.1)",
                        path.getFileName(), child, parent, parts[2].trim()));
            }
            taxonomy.addEdge(child, parent, weight);
        }
    }

    /**
     * Loads utility.txt and probability.txt TOGETHER (2 separate files, one
     * transaction per line, same order in both files) and computes
     * EU(i,T) = u(i,T) x p(i,T) before handing the transaction to the algo.
     *
     * Validates, and FAILS FAST with a descriptive IllegalArgumentException,
     * on:
     *   - probability.txt missing, or having a different number of
     *     non-empty lines than utility.txt
     *   - a line whose item list does not match, item-for-item, the
     *     corresponding utility.txt line (ordering must be identical since
     *     probabilities are matched positionally)
     *   - any p(i,T) outside (0, 1] (existential probability, Muc 1)
     *
     * @param utilityPath     path to {@code utility.txt}
     * @param probabilityPath path to the probability file (e.g. {@code probability.txt} or a generated {@code probability_<level>_seed<N>.txt})
     * @param algo            the {@link UTKMLHUIAlgo} instance to register transactions into
     * @throws IOException              if either file cannot be read
     * @throws IllegalArgumentException if the files are missing, mismatched, or contain an invalid probability
     */
    private static void loadUtilityAndProbability(Path utilityPath, Path probabilityPath, UTKMLHUIAlgo algo) throws IOException {
        if (!Files.exists(utilityPath)) {
            throw new IllegalArgumentException("Missing required file: " + utilityPath);
        }
        if (!Files.exists(probabilityPath)) {
            throw new IllegalArgumentException("Missing required probability file: " + probabilityPath
                    + " (U-TK-MLHUI requires an existential probability p(i,T) for every item "
                    + "in every transaction -- see Muc 1 of the spec). Pass a different file name "
                    + "as a trailing .txt argument if you don't want to use probability.txt.");
        }

        List<String> utilLines = nonEmptyLines(utilityPath);
        List<String> probLines = nonEmptyLines(probabilityPath);

        if (utilLines.size() != probLines.size()) {
            throw new IllegalArgumentException(String.format(
                    "utility.txt has %d transaction line(s) but %s has %d -- they must match 1-for-1, same order.",
                    utilLines.size(), probabilityPath.getFileName(), probLines.size()));
        }

        for (int lineNo = 0; lineNo < utilLines.size(); lineNo++) {
            String uLine = utilLines.get(lineNo);
            String pLine = probLines.get(lineNo);

            String[] uParts = uLine.split(":");
            if (uParts.length < 3) {
                throw new IllegalArgumentException("utility.txt line " + (lineNo + 1)
                        + " does not match 'items : TWU : utilities' format: " + uLine);
            }
            String[] itemTok = uParts[0].trim().split("\\s+");
            String[] utilTok = uParts[2].trim().split("\\s+");
            int n = itemTok.length;
            if (utilTok.length != n) {
                throw new IllegalArgumentException("utility.txt line " + (lineNo + 1)
                        + ": item count (" + n + ") does not match utility-value count (" + utilTok.length + ")");
            }

            String[] pParts = pLine.split(":");
            if (pParts.length < 2) {
                throw new IllegalArgumentException(probabilityPath.getFileName() + " line " + (lineNo + 1)
                        + " does not match 'items : probabilities' format: " + pLine);
            }
            String[] pItemTok = pParts[0].trim().split("\\s+");
            String[] probTok = pParts[1].trim().split("\\s+");
            if (probTok.length != n) {
                throw new IllegalArgumentException(probabilityPath.getFileName() + " line " + (lineNo + 1)
                        + ": probability count (" + probTok.length + ") does not match item count (" + n
                        + ") from the matching utility.txt line " + (lineNo + 1));
            }
            if (pItemTok.length != n) {
                throw new IllegalArgumentException(probabilityPath.getFileName() + " line " + (lineNo + 1)
                        + ": item count (" + pItemTok.length + ") does not match utility.txt line " + (lineNo + 1)
                        + " (" + n + " items)");
            }

            int[] itemIds = new int[n];
            double[] eu = new double[n];
            for (int i = 0; i < n; i++) {
                int itemId = Integer.parseInt(itemTok[i]);
                int probItemId = Integer.parseInt(pItemTok[i]);
                if (itemId != probItemId) {
                    throw new IllegalArgumentException(String.format(
                            "Item mismatch at line %d, position %d: utility.txt has item %d but %s has item %d "
                                    + "-- both files must list items in the SAME order per transaction.",
                            lineNo + 1, i + 1, itemId, probabilityPath.getFileName(), probItemId));
                }
                double u = Double.parseDouble(utilTok[i]);
                double p = Double.parseDouble(probTok[i]);
                if (!(p > 0.0 && p <= 1.0)) {
                    throw new IllegalArgumentException(String.format(
                            "Invalid existential probability at %s line %d, item %d: p=%s is not in (0, 1]. "
                                    + "(Muc 1 cua dac ta: p(i,T) in (0,1])",
                            probabilityPath.getFileName(), lineNo + 1, itemId, probTok[i]));
                }
                itemIds[i] = itemId;
                eu[i] = u * p; // EU(i,T) = u(i,T) x p(i,T)  (Muc 1.1, cong thuc mo rong (1))
            }
            algo.addTransaction(itemIds, eu);
        }
    }

    /**
     * Loads the new combined pipeline format, {@code utility_probability.txt}
     * ({@code items : TWU : utilities : probabilities}, ONE file, leaf items
     * only -- see TOM_TAT_QUY_TRINH.md), computing EU(i,T) = u(i,T) x p(i,T)
     * before handing each transaction to the algo. In the current linguistic
     * dataset the probabilities column is always {@code 1.0} for every item
     * (all uncertainty has moved to the taxonomy's edge weights instead, per
     * Dinh nghia 3.2's note) -- this loader still reads and validates that
     * column rather than assuming it, so it keeps working unchanged if a
     * future dataset ever ships a real per-item p(i,T) again.
     *
     * @param path path to {@code utility_probability.txt}
     * @param algo the {@link UTKMLHUIAlgo} instance to register transactions into
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if a line is malformed or a probability is outside (0, 1]
     */
    private static void loadUtilityProbabilityCombined(Path path, UTKMLHUIAlgo algo) throws IOException {
        List<String> lines = nonEmptyLines(path);
        for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo);
            String[] parts = line.split(":");
            if (parts.length < 4) {
                throw new IllegalArgumentException(path.getFileName() + " line " + (lineNo + 1)
                        + " does not match 'items : TWU : utilities : probabilities' format: " + line);
            }
            String[] itemTok = parts[0].trim().split("\\s+");
            String[] utilTok = parts[2].trim().split("\\s+");
            String[] probTok = parts[3].trim().split("\\s+");
            int n = itemTok.length;
            if (utilTok.length != n || probTok.length != n) {
                throw new IllegalArgumentException(path.getFileName() + " line " + (lineNo + 1)
                        + ": item count (" + n + ") does not match utility count (" + utilTok.length
                        + ") or probability count (" + probTok.length + ")");
            }
            int[] itemIds = new int[n];
            double[] eu = new double[n];
            for (int i = 0; i < n; i++) {
                int itemId = Integer.parseInt(itemTok[i]);
                double u = Double.parseDouble(utilTok[i]);
                double p = Double.parseDouble(probTok[i]);
                if (!(p > 0.0 && p <= 1.0)) {
                    throw new IllegalArgumentException(String.format(
                            "Invalid existential probability at %s line %d, item %d: p=%s is not in (0, 1]. "
                                    + "(Dinh nghia 3.2: p(i,T) in (0,1])",
                            path.getFileName(), lineNo + 1, itemId, probTok[i]));
                }
                itemIds[i] = itemId;
                eu[i] = u * p; // EU(i,T) = u(i,T) x p(i,T)   (Dinh nghia 3.3)
            }
            algo.addTransaction(itemIds, eu);
        }
    }

    /**
     * Reads every non-empty, trimmed line from a file, in order.
     *
     * @param path path to the file to read
     * @return the non-empty lines, trimmed
     * @throws IOException if the file cannot be read
     */
    private static List<String> nonEmptyLines(Path path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
     * Writes the ranked top-k patterns (rank, expected utility, and item names) to a text file.
     *
     * @param path       output file path
     * @param algo       the algorithm instance (used to resolve item display names)
     * @param results    the top-k patterns, already sorted by descending expected utility
     * @param datasetDir the dataset name shown in the file header
     * @param k          the top-k parameter, shown in the file header
     * @throws IOException if the file cannot be written
     */
    private static void writeTopK(Path path, UTKMLHUIAlgo algo, List<UTKMLHUIAlgo.Pattern> results,
                                   String datasetDir, int k) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("Dataset: " + datasetDir + " | k = " + k);
            w.newLine();
            w.write("Rank\tExpected Utility (EU)\tPattern (id:name)");
            w.newLine();
            int rank = 1;
            for (UTKMLHUIAlgo.Pattern p : results) {
                StringBuilder sb = new StringBuilder();
                for (int id : p.itemIds) {
                    if (sb.length() > 0) sb.append(", ");
                    Item it = algo.getItem(id);
                    String name = (it != null && it.name != null) ? it.name : String.valueOf(id);
                    sb.append(id).append(":").append(name);
                }
                w.write(rank + "\t" + String.format("%.6f", p.utility) + "\t{" + sb + "}");
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
     * Appends one row per run so that running all 5 variants (in any order,
     * across separate invocations) accumulates into a single side-by-side
     * comparison table instead of each run overwriting the last one's result.
     * Uses the SAME output_performance.txt / column layout as mlTKO and
     * TK-MLHUI so all three algorithms line up in one table.
     *
     * @param path       output file path (created with a header row if it does not yet exist)
     * @param algo       the algorithm instance the metrics are read from
     * @param k          the top-k parameter for this run
     * @param datasetDir the dataset name for this row (possibly annotated with a non-default probability file name)
     * @param variant    the ablation variant that produced this run
     * @throws IOException if the file cannot be written
     */
    private static void writePerformance(Path path, UTKMLHUIAlgo algo, int k, String datasetDir,
                                          UTKMLHUIAlgo.Variant variant) throws IOException {
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
    private static void writeDebugTrace(Path path, UTKMLHUIAlgo algo) throws IOException {
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
    private static void writeMuLog(Path path, UTKMLHUIAlgo algo, String datasetDir, int k) throws IOException {
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
