package mltko;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads the three input files of a dataset folder into in-memory model
 * objects ({@link Transaction}, {@link TaxonomyTree}, and an item-id to
 * item-name map).
 *
 * Every dataset (FruitHut or any other one dropped in later) is
 * expected to follow the same generic naming convention:
 *   {@code name.txt} - lines of the form {@code @ITEM=id=name}
 *   {@code taxonomy.txt} - lines of the form {@code child_id,parent_id}
 *   {@code utility.txt} - lines of the form {@code items : total_utility : utils}
 *
 * File lookup is case-insensitive and also accepts the older
 * FruitHut-style names ({@code item_name.txt}, {@code fruithut_taxonomy_data.txt},
 * {@code fruithut_utility.txt}) as a fallback, so existing datasets keep
 * working without renaming.
 *
 * Parsing is defensive: blank or malformed lines are skipped (counted
 * internally) instead of crashing the whole load.
 */
public final class DataLoader {

    // Candidate file names for each input file, tried in order (current convention first, legacy FruitHut names as fallback).
    private static final String[] NAME_FILE_CANDIDATES = { "name.txt", "item_name.txt" };
    private static final String[] TAXONOMY_FILE_CANDIDATES = { "taxonomy.txt", "fruithut_taxonomy_data.txt" };
    private static final String[] UTILITY_FILE_CANDIDATES = { "utility.txt", "fruithut_utility.txt" };

    /** Item id to display-name map, populated by {@link #loadAll}. */
    public final Map<Integer, String> itemNames = new HashMap<>();
    /** The taxonomy (is-a hierarchy) tree, populated by {@link #loadAll}. */
    public final TaxonomyTree taxonomy = new TaxonomyTree();
    /** All parsed transactions, in file order, populated by {@link #loadAll}. */
    public final List<Transaction> transactions = new ArrayList<>();

    /** Creates a loader with empty {@link #itemNames}, {@link #taxonomy} and {@link #transactions}, ready for {@link #loadAll}. */
    public DataLoader() {
    }

    /**
     * Loads {@code name.txt}, {@code taxonomy.txt} and {@code utility.txt}
     * (or their legacy-named equivalents) from the given folder into
     * {@link #itemNames}, {@link #taxonomy} and {@link #transactions}
     * respectively.
     *
     * @param folder path to the dataset folder to load
     * @throws IOException if {@code utility.txt} (or its legacy
     *                      equivalent) cannot be found in {@code folder},
     *                      or if an I/O error occurs while reading any of
     *                      the three files
     */
    public void loadAll(String folder) throws IOException {
        Path dir = Paths.get(folder);
        Path itemNameFile = findFile(dir, NAME_FILE_CANDIDATES);
        Path taxonomyFile = findFile(dir, TAXONOMY_FILE_CANDIDATES);
        Path utilityFile = findFile(dir, UTILITY_FILE_CANDIDATES);

        if (itemNameFile != null) loadItemNames(itemNameFile);

        if (taxonomyFile != null) loadTaxonomy(taxonomyFile);

        if (utilityFile != null) loadTransactions(utilityFile);
        else throw new IOException("utility.txt not found in " + folder + " - cannot proceed.");
    }

    /**
     * Case-insensitive file lookup inside a directory, trying each
     * candidate name in order (first match wins).
     *
     * @param dir                  the directory to search (non-recursive)
     * @param candidateNamesLower  candidate file names to try, in priority order
     * @return the matching {@link Path}, or {@code null} if {@code dir}
     *         does not exist or none of the candidates match
     * @throws IOException if an I/O error occurs while listing {@code dir}
     */
    private Path findFile(Path dir, String[] candidateNamesLower) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        for (String targetNameLower : candidateNamesLower) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    if (p.getFileName().toString().equalsIgnoreCase(targetNameLower)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parses a {@code name.txt}-style file (lines of the form
     * {@code @ITEM=id=name}) into {@link #itemNames}. Lines that do not
     * start with {@code @ITEM=}, or whose id is not a valid integer, are
     * skipped.
     *
     * @param file path to the item-name file to parse
     * @throws IOException if an I/O error occurs while reading {@code file}
     */
    private void loadItemNames(Path file) throws IOException {
        int lineNo = 0, errors = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("@ITEM=")) continue;
                String body = line.substring("@ITEM=".length());
                int eq = body.indexOf('=');
                if (eq < 0) { errors++; continue; }
                String idStr = body.substring(0, eq).trim();
                String name = body.substring(eq + 1).trim();
                try {
                    int id = Integer.parseInt(idStr);
                    itemNames.put(id, name);
                } catch (NumberFormatException nfe) {
                    errors++;
                }
            }
        }
    }

    /**
     * Parses a {@code taxonomy.txt}-style file (lines of the form
     * {@code child_id,parent_id}, comma- or whitespace-separated) into
     * {@link #taxonomy} via {@link TaxonomyTree#addEdge}. Malformed lines
     * (fewer than 2 fields, or non-integer ids) are skipped.
     *
     * @param file path to the taxonomy file to parse
     * @throws IOException if an I/O error occurs while reading {@code file}
     */
    private void loadTaxonomy(Path file) throws IOException {
        int errors = 0, ok = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("[,\\s]+");
                if (parts.length < 2) { errors++; continue; }
                try {
                    int child = Integer.parseInt(parts[0].trim());
                    int par = Integer.parseInt(parts[1].trim());
                    taxonomy.addEdge(child, par);
                    ok++;
                } catch (NumberFormatException nfe) {
                    errors++;
                }
            }
        }
    }

    /**
     * Parses a {@code utility.txt}-style file (lines of the form
     * {@code item1 item2 ... : total_utility : util1 util2 ...}) into
     * {@link #transactions}. Each valid line becomes one {@link
     * Transaction}, assigned a zero-based, file-order transaction id.
     * Malformed lines (wrong field count, mismatched item/utility counts,
     * or non-numeric values) are skipped.
     *
     * @param file path to the utility/transaction file to parse
     * @throws IOException if an I/O error occurs while reading {@code file}
     */
    private void loadTransactions(Path file) throws IOException {
        int tid = 0, errors = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(":");
                if (parts.length != 3) { errors++; continue; }
                try {
                    String[] itemStrs = parts[0].trim().split("\\s+");
                    long totalUtility = Long.parseLong(parts[1].trim());
                    String[] utilStrs = parts[2].trim().split("\\s+");
                    if (itemStrs.length != utilStrs.length || itemStrs.length == 0) { errors++; continue; }

                    int n = itemStrs.length;
                    int[] items = new int[n];
                    long[] utils = new long[n];
                    for (int i = 0; i < n; i++) {
                        items[i] = Integer.parseInt(itemStrs[i].trim());
                        utils[i] = Long.parseLong(utilStrs[i].trim());
                    }
                    transactions.add(new Transaction(tid++, items, utils, totalUtility));
                } catch (NumberFormatException nfe) {
                    errors++;
                }
            }
        }
    }
}
