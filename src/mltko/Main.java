package mltko;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Command-line entry point for the mlTKO (Top-K Multi-Level High Utility
 * Itemset Mining) project.
 *
 * Usage:
 *   java mltko.Main [data_folder] [k_file] [output_dir] [variant]
 *
 * Arguments (all optional, positional):
 *   data_folder - folder containing {@code name.txt}, {@code taxonomy.txt}
 *       and {@code utility.txt} for ONE dataset (e.g. {@code "input/fruithut"}).
 *       If omitted, the program asks for it interactively at startup,
 *       showing the datasets it finds under {@code "input/"} as a hint.
 *       This way the same jar/build can be reused for any number of
 *       datasets - just drop a new folder with those 3 files under
 *       {@code "input/"} and type its name/path.
 *   k_file - a plain text file whose content is just the integer k
 *       (default: {@code "k.txt"} in the current directory, falling back
 *       to {@code "<data_folder>/k.txt"}, falling back to k=10 if neither
 *       exists).
 *   output_dir - where the output files are written (default: {@code "output"}).
 *   variant - which ablation variant(s) to run: {@code "all"} (default)
 *       to run every variant in {@link #VARIANTS} back to back, or the
 *       name of exactly one variant ({@code "mltko"}, {@code "mltko-nop"},
 *       or {@code "mltko-wo-merge"}, case-insensitive) to run only that
 *       one. See {@link #selectVariants(String)}.
 *
 * Ablation study (Section 5 of the mlTKO paper, plus one bonus variant).
 * The available variants are:
 *
 *   mltko          useEUCP=true,  useMergeOptimization=true  - paper's full mlTKO (baseline)
 *   mltko-nop      useEUCP=false, useMergeOptimization=true  - paper's mlTKO-nop ablation baseline (EUCP off)
 *   mltko-wo-merge useEUCP=true,  useMergeOptimization=false - bonus variant, NOT in the paper,
 *                                                               isolates the merge optimisation alone
 *
 * Passing variant="all" runs all three, back to back, on the SAME loaded
 * dataset, so their candidate counts / timings are directly comparable.
 * Passing a single variant name runs only that one (useful for a quick
 * timing run without paying for the other two).
 *
 * Turning EUCP or the merge optimisation off only removes a pruning /
 * dedup step - it must never change WHICH Top-K itemsets are found, only
 * how many candidates are visited to find them. {@code output_ablation_summary.txt}
 * checks exactly that when 2 or more variants were run in the same
 * invocation (see {@link ResultWriter#writeAblationSummary}).
 *
 * Examples:
 *   java mltko.Main                                        # asks for folder name, runs all 3 variants
 *   java mltko.Main input/fruithut k.txt output             # runs all 3 variants
 *   java mltko.Main input/fruithut k.txt output all         # same as above, explicit
 *   java mltko.Main input/fruithut k.txt output mltko-nop   # runs only mlTKO-nop
 *
 * Output files (inside output_dir), once per variant name {@code {V}}
 * that was actually run:
 *   output_topk_patterns_{V}.txt      - the Top-K MLHUPs found by variant V
 *   output_mu_threshold_log_{V}.txt   - mu trajectory from the initial
 *       value (Sec. 4.1) through every RUC raise, for variant V
 *
 * plus, shared across every variant run this invocation:
 *   output_performance.txt       - one row per variant run this
 *       invocation (appended across runs, never overwritten)
 *   output_ablation_summary.txt - side-by-side candidate/time comparison,
 *       and - when 2 or more variants were run - a correctness check that
 *       they all agree on the Top-K itemsets (overwritten each run)
 *   output_level_debug.txt      - debug report of shortest-path vs.
 *       longest-path level for every category (variant-independent,
 *       computed once)
 *
 * Terminal output is limited to exactly two status lines per successful run:
 *   {@code Running mlTKO ablation study on dataset: <data_folder>  (k=<k>)}
 *   {@code Done. <N> pattern(s) found (<first variant run>). <M> variant(s) run. Output written to: <output_dir>}
 *
 * If variant does not name "all" or one of the known variants, a single
 * error line listing the valid options is printed instead, and nothing is
 * loaded, run, or written.
 */
public final class Main {

    /** Default parent folder scanned by {@link #promptForDataFolder()} for available datasets. */
    private static final String DEFAULT_INPUT_ROOT = "input";

    /** Not instantiable: {@link Main} is a static-only command-line entry point. */
    private Main() {
    }

    /**
     * One row of the ablation study: a variant name plus the two boolean
     * switches (forwarded to {@link MlTKOAlgorithm}'s constructor) that
     * define it.
     */
    private static final class Variant {
        /** Display/file name of this variant, e.g. {@code "mltko-nop"}. */
        final String name;
        /** Whether this variant enables FHM's EUCP pruning check. */
        final boolean useEUCP;
        /** Whether this variant enables the (non-paper) merge optimisation. */
        final boolean useMergeOptimization;

        /**
         * Creates one ablation-study variant descriptor.
         *
         * @param name                  display/file name of the variant
         * @param useEUCP               whether to enable EUCP pruning
         * @param useMergeOptimization  whether to enable the merge optimisation
         */
        Variant(String name, boolean useEUCP, boolean useMergeOptimization) {
            this.name = name;
            this.useEUCP = useEUCP;
            this.useMergeOptimization = useMergeOptimization;
        }
    }

    /** Name of the full mlTKO variant, used as {@link #VARIANTS}' first (default baseline) entry. */
    private static final String BASELINE_VARIANT = "mltko";

    /**
     * The full set of known ablation variants, in default ("all") run
     * order. Paper's own ablation (Sec. 5): mlTKO (EUCP on) vs. mlTKO-nop
     * (EUCP off). mlTKO-wo-merge is an extra, clearly-labelled bonus
     * variant that is NOT part of the original paper - it isolates the
     * (non-paper) merge optimisation in {@link MlTKOAlgorithm} instead of
     * the paper's own EUCP switch. Which of these actually run in a given
     * invocation is decided by {@link #selectVariants(String)}.
     */
    private static final List<Variant> VARIANTS = List.of(
            new Variant(BASELINE_VARIANT, true, true),
            new Variant("mltko-nop", false, true),
            new Variant("mltko-wo-merge", true, false)
    );

    /**
     * Resolves the {@code variant} CLI argument into the list of {@link
     * Variant Variants} to actually run this invocation.
     *
     * @param selector {@code "all"} (case-insensitive) to select every
     *                 entry in {@link #VARIANTS}, or the name of exactly
     *                 one variant (case-insensitive) to select just that one
     * @return {@link #VARIANTS} unmodified if {@code selector} is
     *         {@code "all"}; a single-element list containing the matching
     *         variant if {@code selector} names one; or an empty list if
     *         {@code selector} matches neither (the caller should treat
     *         this as an invalid-argument error)
     */
    private static List<Variant> selectVariants(String selector) {
        if (selector.equalsIgnoreCase("all")) return VARIANTS;
        for (Variant v : VARIANTS) {
            if (v.name.equalsIgnoreCase(selector)) return List.of(v);
        }
        return List.of();
    }

    /**
     * Builds the comma-separated list of valid {@code variant} values, for
     * use in the invalid-argument error message.
     *
     * @return {@code "all"} followed by every entry of {@link #VARIANTS}, comma-separated
     */
    private static String validVariantSelectors() {
        List<String> names = new ArrayList<>();
        names.add("all");
        for (Variant v : VARIANTS) names.add(v.name);
        return String.join(", ", names);
    }

    /**
     * Program entry point. Loads one dataset, runs the {@link
     * #selectVariants(String) selected ablation variant(s)} of {@link
     * MlTKOAlgorithm} against it, and writes all result/performance/
     * ablation files via {@link ResultWriter}.
     *
     * Any {@link IOException} or other runtime exception during loading
     * or writing is swallowed silently, by design: the terminal contract
     * is exactly two status lines per successful run (see the class-level
     * Javadoc). An unrecognised {@code variant} argument is the one
     * exception: it is reported as a single error line, since it reflects
     * a usage mistake rather than a runtime failure.
     *
     * @param args command-line arguments, positionally:
     *             {@code [data_folder] [k_file] [output_dir] [variant]}, all optional
     */
    public static void main(String[] args) {
        String dataFolder = args.length > 0 && !args[0].isBlank() ? args[0] : promptForDataFolder();
        String kFileArg = args.length > 1 ? args[1] : null;
        String outputDir = args.length > 2 ? args[2] : "output";
        String variantArg = args.length > 3 && !args[3].isBlank() ? args[3] : "all";

        List<Variant> selected = selectVariants(variantArg);
        if (selected.isEmpty()) {
            System.out.println("Unknown variant \"" + variantArg + "\". Valid options: " + validVariantSelectors() + ".");
            return;
        }

        try {
            if (!Files.isDirectory(Paths.get(dataFolder))
                    || (findCaseInsensitive(dataFolder, "utility.txt") == null
                        && findCaseInsensitive(dataFolder, "fruithut_utility.txt") == null)) {
                SampleDataGenerator.generate(dataFolder);
            }

            int k = readK(kFileArg, dataFolder);
            String datasetName = datasetNameOf(dataFolder);

            System.out.println("Running mlTKO ablation study on dataset: " + dataFolder + "  (k=" + k + ")");

            Files.createDirectories(Paths.get(outputDir));

            DataLoader loader = new DataLoader();
            loader.loadAll(dataFolder);

            if (loader.transactions.isEmpty()) {
                return;
            }

            // --- run every selected variant on the SAME loaded dataset ---
            List<PerformanceMetrics> allMetrics = new ArrayList<>();
            LinkedHashMap<String, List<Pattern>> resultsByVariant = new LinkedHashMap<>();

            for (Variant v : selected) {
                MlTKOAlgorithm algo = new MlTKOAlgorithm(loader.transactions, loader.taxonomy,
                        loader.itemNames, k, v.useEUCP, v.useMergeOptimization);
                List<Pattern> topKResult = algo.run();
                resultsByVariant.put(v.name, topKResult);

                allMetrics.add(new PerformanceMetrics(v.name, k, datasetName,
                        algo.executionTimeMs, algo.peakMemoryMB, algo.candidatesGenerated, algo.scannedItemLists));

                String topKPath = Paths.get(outputDir, "output_topk_patterns_" + v.name + ".txt").toString();
                String muLogPath = Paths.get(outputDir, "output_mu_threshold_log_" + v.name + ".txt").toString();
                ResultWriter.writeTopKPatterns(topKPath, datasetName, k, topKResult, loader.itemNames);
                ResultWriter.writeMuThresholdLog(muLogPath, datasetName, k, algo.muLog, loader.itemNames);
            }

            String perfPath = Paths.get(outputDir, "output_performance.txt").toString();
            String ablationPath = Paths.get(outputDir, "output_ablation_summary.txt").toString();
            String levelDebugPath = Paths.get(outputDir, "output_level_debug.txt").toString();

            String baselineName = selected.get(0).name;
            ResultWriter.writePerformance(perfPath, allMetrics);
            ResultWriter.writeAblationSummary(ablationPath, datasetName, k, baselineName,
                    resultsByVariant, allMetrics, loader.itemNames);
            ResultWriter.writeLevelDebug(levelDebugPath, datasetName, k, loader.taxonomy, loader.itemNames);

            int baselineCount = resultsByVariant.getOrDefault(baselineName, List.of()).size();
            System.out.println("Done. " + baselineCount + " pattern(s) found (" + baselineName + "). "
                    + selected.size() + " variant(s) run. Output written to: " + outputDir);

        } catch (IOException e) {
            // Intentionally silent per spec: only the two status lines above go to the terminal.
        } catch (Exception e) {
            // Intentionally silent per spec: only the two status lines above go to the terminal.
        }
    }

    /**
     * Derives a best-effort, human-readable dataset name for report
     * headers: simply the data folder's own last path segment.
     *
     * @param dataFolder path to the dataset folder
     * @return the folder's file name, or {@code dataFolder} itself if it has no name component
     */
    private static String datasetNameOf(String dataFolder) {
        Path p = Paths.get(dataFolder).getFileName();
        return p != null ? p.toString() : dataFolder;
    }

    /**
     * Asks the user, on stdin, which dataset folder to run. Shows every
     * subfolder found under {@code "input/"} (each expected to hold its
     * own {@code name.txt} / {@code taxonomy.txt} / {@code utility.txt})
     * as a hint, so the user can just type its name. Nothing is hardcoded
     * to any particular dataset: add as many folders under
     * {@code "input/"} as needed for future datasets.
     *
     * @return the dataset folder path to use: the user's typed value
     *         (resolved under {@code input/} if it names an existing
     *         subfolder there, otherwise used verbatim), or the first
     *         available dataset under {@code input/} if nothing was
     *         typed, or {@code "input/sample"} as a last resort so
     *         {@link SampleDataGenerator} can populate it
     */
    private static String promptForDataFolder() {
        List<String> available = listAvailableDatasets(DEFAULT_INPUT_ROOT);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Nhap ten (hoac duong dan) thu muc du lieu input");
        if (!available.isEmpty()) {
            prompt.append(" [").append(String.join(", ", available)).append("]");
        }
        prompt.append(": ");

        Scanner scanner = new Scanner(System.in);
        System.out.print(prompt);
        String typed = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        if (!typed.isEmpty()) {
            // Allow typing either just the folder name (resolved under
            // input/) or a full/relative path.
            Path asChild = Paths.get(DEFAULT_INPUT_ROOT, typed);
            if (Files.isDirectory(asChild)) return asChild.toString();
            return typed;
        }
        if (!available.isEmpty()) {
            return Paths.get(DEFAULT_INPUT_ROOT, available.get(0)).toString();
        }
        // Nothing typed and nothing found: fall back to a fresh folder name;
        // Main will auto-generate a small self-test sample dataset there.
        return Paths.get(DEFAULT_INPUT_ROOT, "sample").toString();
    }

    /**
     * Lists the immediate subfolders of a root directory, alphabetically.
     *
     * @param root path to the root directory to scan (typically {@code "input/"})
     * @return the sorted names of all immediate subdirectories, or an empty list if {@code root} does not exist
     */
    private static List<String> listAvailableDatasets(String root) {
        List<String> names = new ArrayList<>();
        Path dir = Paths.get(root);
        if (!Files.isDirectory(dir)) return names;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) names.add(p.getFileName().toString());
            }
        } catch (IOException ignored) { }
        Collections.sort(names);
        return names;
    }

    /**
     * Reads the Top-K parameter k from, in priority order: the explicit
     * path given as the 2nd CLI argument, {@code "k.txt"} in the current
     * directory, then {@code "<data_folder>/k.txt"}. Falls back to 10 if
     * none of those files exist or none contain a parseable integer.
     *
     * @param explicitPath explicit path to a k-file (the CLI's 2nd argument), or {@code null} if not given
     * @param dataFolder   the dataset folder, used to look for a fallback {@code k.txt} inside it
     * @return the parsed value of k, or {@code 10} if it could not be determined
     */
    private static int readK(String explicitPath, String dataFolder) {
        List<Path> candidates = new ArrayList<>();
        if (explicitPath != null) candidates.add(Paths.get(explicitPath));
        candidates.add(Paths.get("k.txt"));
        candidates.add(Paths.get(dataFolder, "k.txt"));

        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+").matcher(content);
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group());
                    }
                } catch (IOException | NumberFormatException ignored) { }
            }
        }
        return 10;
    }

    /**
     * Looks for a file inside a folder by name, ignoring case.
     *
     * @param folder      the folder to search (non-recursive)
     * @param targetLower the target file name (comparison is case-insensitive)
     * @return the matching {@link Path}, or {@code null} if {@code folder}
     *         does not exist or contains no matching file
     */
    private static Path findCaseInsensitive(String folder, String targetLower) {
        try {
            Path dir = Paths.get(folder);
            if (!Files.isDirectory(dir)) return null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    if (p.getFileName().toString().equalsIgnoreCase(targetLower)) return p;
                }
            }
        } catch (IOException ignored) { }
        return null;
    }
}
