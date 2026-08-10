package mltko;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads the three input files of a dataset folder. Every dataset (FruitHut
 * or any other one the user drops in later) is expected to follow the same
 * generic naming convention:
 *   - name.txt       (@ITEM=id=name)
 *   - taxonomy.txt   (child_id,parent_id)
 *   - utility.txt    (items : total_utility : utils)
 *
 * File lookup is case-insensitive and also accepts the older FruitHut-style
 * names (item_name.txt / fruithut_taxonomy_data.txt / fruithut_utility.txt)
 * as a fallback, so existing datasets keep working without renaming.
 *
 * Parsing is defensive: blank / malformed lines are skipped with a warning
 * on stderr instead of crashing the whole load.
 */
public final class DataLoader {

    private static final String[] NAME_FILE_CANDIDATES = { "name.txt", "item_name.txt" };
    private static final String[] TAXONOMY_FILE_CANDIDATES = { "taxonomy.txt", "fruithut_taxonomy_data.txt" };
    private static final String[] UTILITY_FILE_CANDIDATES = { "utility.txt", "fruithut_utility.txt" };

    public final Map<Integer, String> itemNames = new HashMap<>();
    public final TaxonomyTree taxonomy = new TaxonomyTree();
    public final List<Transaction> transactions = new ArrayList<>();

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
     * Case-insensitive file lookup inside a directory, trying each candidate
     * name in order (first match wins).
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
