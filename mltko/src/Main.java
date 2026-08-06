package mltko;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Usage:
 *   java mltko.Main [data_folder] [k_file] [output_dir]
 *
 *   data_folder : folder containing name.txt, taxonomy.txt and utility.txt
 *                 for ONE dataset (e.g. "input/fruithut"). If omitted on
 *                 the command line, the program asks for it interactively
 *                 at startup, showing the datasets it finds under "input/"
 *                 as a hint. This way the same jar/build can be reused for
 *                 any number of datasets -- just drop a new folder with
 *                 those 3 files under "input/" and type its name/path.
 *   k_file      : a plain text file whose content is just the integer k
 *                 (default: "k.txt" in the current directory, falling back
 *                 to "<data_folder>/k.txt", falling back to k=10 if neither
 *                 exists)
 *   output_dir  : where the output files are written (default: "output")
 *
 * Only the paper's main algorithm (mlTKO, EUCP + merge optimization
 * enabled) is run -- the mlTKO-nop / mlTKO-wo-merge ablation baselines
 * have been removed; this build always runs the full mlTKO variant.
 *
 * Examples:
 *   java mltko.Main                                    # asks for folder name
 *   java mltko.Main input/fruithut k.txt output
 *
 * Produces (inside output_dir):
 *   output_topk_patterns.txt     -- the Top-K MLHUPs (mlTKO)
 *   output_mu_threshold_log.txt  -- mu trajectory from the initial value
 *                                   (Sec. 4.1) through every RUC raise
 *   output_performance.txt       -- performance metrics for the mlTKO run
 *                                   (APPENDED across runs, one row per run --
 *                                   not overwritten)
 *   output_level_debug.txt       -- DEBUG: level(min)/shortest-path vs
 *                                   level(max)/longest-path for every
 *                                   category
 *
 * Terminal output is limited to exactly two status lines per run:
 *   Running TK-MLHUI on dataset: <data_folder>  (k=<k>)
 *   Done. <N> pattern(s) found. Output written to: <output_dir>
 */
public final class Main {

    private static final String DEFAULT_INPUT_ROOT = "input";
    private static final String VARIANT_NAME = "mltko";

    public static void main(String[] args) {
        String dataFolder = args.length > 0 && !args[0].isBlank() ? args[0] : promptForDataFolder();
        String kFileArg = args.length > 1 ? args[1] : null;
        String outputDir = args.length > 2 ? args[2] : "output";

        try {
            if (!Files.isDirectory(Paths.get(dataFolder))
                    || (findCaseInsensitive(dataFolder, "utility.txt") == null
                        && findCaseInsensitive(dataFolder, "fruithut_utility.txt") == null)) {
                SampleDataGenerator.generate(dataFolder);
            }

            int k = readK(kFileArg, dataFolder);
            String datasetName = datasetNameOf(dataFolder);

            System.out.println("Running TK-MLHUI on dataset: " + dataFolder + "  (k=" + k + ")");

            Files.createDirectories(Paths.get(outputDir));

            DataLoader loader = new DataLoader();
            loader.loadAll(dataFolder);

            if (loader.transactions.isEmpty()) {
                return;
            }

            // --- mlTKO (paper's full algorithm: EUCP + merge optimization enabled) ---
            MlTKOAlgorithm full = new MlTKOAlgorithm(loader.transactions, loader.taxonomy,
                    loader.itemNames, k, true, true);
            List<Pattern> topKResult = full.run();

            List<PerformanceMetrics> allMetrics = new ArrayList<>();
            allMetrics.add(new PerformanceMetrics(VARIANT_NAME, k, datasetName,
                    full.executionTimeMs, full.peakMemoryMB, full.candidatesGenerated, full.scannedItemLists));

            String topKPath = Paths.get(outputDir, "output_topk_patterns.txt").toString();
            String muLogPath = Paths.get(outputDir, "output_mu_threshold_log.txt").toString();
            String perfPath = Paths.get(outputDir, "output_performance.txt").toString();
            String levelDebugPath = Paths.get(outputDir, "output_level_debug.txt").toString();

            ResultWriter.writeTopKPatterns(topKPath, datasetName, k, topKResult, loader.itemNames);
            ResultWriter.writeMuThresholdLog(muLogPath, datasetName, k, full.muLog, loader.itemNames);
            ResultWriter.writePerformance(perfPath, allMetrics);
            ResultWriter.writeLevelDebug(levelDebugPath, datasetName, k, loader.taxonomy, loader.itemNames);

            System.out.println("Done. " + topKResult.size() + " pattern(s) found. Output written to: " + outputDir);

        } catch (IOException e) {
            // Intentionally silent per spec: only the two status lines above go to the terminal.
        } catch (Exception e) {
            // Intentionally silent per spec: only the two status lines above go to the terminal.
        }
    }

    /** Best-effort human-readable dataset name for headers: the folder's own name. */
    private static String datasetNameOf(String dataFolder) {
        Path p = Paths.get(dataFolder).getFileName();
        return p != null ? p.toString() : dataFolder;
    }

    /**
     * Asks the user, on stdin, which dataset folder to run. Shows every
     * subfolder found under "input/" (each expected to hold its own
     * name.txt / taxonomy.txt / utility.txt) as a hint, so the user can
     * just type its name. Nothing is hardcoded to any particular dataset:
     * add as many folders under "input/" as needed for future datasets.
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

    /** Lists immediate subfolders of {@code root}, alphabetically. Empty list if root doesn't exist. */
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
     * Reads k from (in priority order): the explicit path given as the 2nd
     * CLI argument, "k.txt" in the current directory, "<data_folder>/k.txt".
     * Falls back to 10 if none exist / content is invalid.
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
