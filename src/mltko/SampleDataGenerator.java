package mltko;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Generates a small but structurally valid FruitHut-style dataset
 * ({@code name.txt} / {@code taxonomy.txt} / {@code utility.txt}) so the
 * program can be demonstrated end-to-end when the requested input folder
 * is missing or incomplete. Replace this folder's contents with a real
 * dataset for actual experiments.
 */
public final class SampleDataGenerator {

    /** Not instantiable: {@link SampleDataGenerator} is a static-only utility class. */
    private SampleDataGenerator() {
    }

    /**
     * Generates a complete sample dataset - 20 leaf items across 3
     * categories under 1 root, plus 500 pseudo-random transactions
     * (fixed seed {@code 42} for reproducibility) - and writes it to
     * {@code folder} as {@code name.txt}, {@code taxonomy.txt}, and
     * {@code utility.txt}.
     *
     * @param folder destination folder, created if it does not already exist
     * @throws IOException if the folder or any of the three files cannot be written
     */
    public static void generate(String folder) throws IOException {
        Path dir = Paths.get(folder);
        Files.createDirectories(dir);

        // --- name.txt ---
        // leaf items 9180-9199 (20 items), simple fruit/grocery names
        String[] names = {
            "Rampe Leaves Dried 50g", "Basil Seed 100g(casa casa)", "Bread Crumbs 500g",
            "Apple Fuji 1kg", "Banana Cavendish 1kg", "Orange Navel 1kg", "Mango Nam Dok Mai 1kg",
            "Grapes Red Seedless 500g", "Watermelon Whole", "Pineapple Whole",
            "Strawberry Punnet 250g", "Blueberry Punnet 125g", "Lemon 500g", "Lime 500g",
            "Carrot 1kg", "Potato 1kg", "Onion Brown 1kg", "Tomato 1kg", "Cucumber 1kg", "Spinach Bunch"
        };
        try (BufferedWriter bw = Files.newBufferedWriter(dir.resolve("name.txt"), StandardCharsets.UTF_8)) {
            for (int i = 0; i < names.length; i++) {
                bw.write("@ITEM=" + (9180 + i) + "=" + names[i]);
                bw.newLine();
            }
        }

        // --- taxonomy.txt ---
        // two categories: 110 = "Herbs&Bakery" (9180-9182), 150 = "Fruits" (9183-9191), 160 = "Vegetables"(9192-9199)
        // plus a root category 500 = "AllGrocery" over 110/150/160 to test multi-level depth.
        try (BufferedWriter bw = Files.newBufferedWriter(dir.resolve("taxonomy.txt"), StandardCharsets.UTF_8)) {
            for (int id = 9180; id <= 9182; id++) { bw.write(id + ",110"); bw.newLine(); }
            for (int id = 9183; id <= 9191; id++) { bw.write(id + ",150"); bw.newLine(); }
            for (int id = 9192; id <= 9199; id++) { bw.write(id + ",160"); bw.newLine(); }
            bw.write("110,500"); bw.newLine();
            bw.write("150,500"); bw.newLine();
            bw.write("160,500"); bw.newLine();
        }

        // --- utility.txt ---
        Random rnd = new Random(42);
        int numTransactions = 500;
        try (BufferedWriter bw = Files.newBufferedWriter(dir.resolve("utility.txt"), StandardCharsets.UTF_8)) {
            for (int t = 0; t < numTransactions; t++) {
                int n = 2 + rnd.nextInt(6); // 2..7 items per transaction
                Set<Integer> chosen = new LinkedHashSet<>();
                while (chosen.size() < n) {
                    chosen.add(9180 + rnd.nextInt(20));
                }
                List<Integer> items = new ArrayList<>(chosen);
                Collections.sort(items);
                List<Integer> utils = new ArrayList<>();
                long total = 0;
                for (int it : items) {
                    int u = 5 + rnd.nextInt(300);
                    utils.add(u);
                    total += u;
                }
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < items.size(); i++) {
                    line.append(items.get(i));
                    if (i < items.size() - 1) line.append(' ');
                }
                line.append(':').append(total).append(':');
                for (int i = 0; i < utils.size(); i++) {
                    line.append(utils.get(i));
                    if (i < utils.size() - 1) line.append(' ');
                }
                bw.write(line.toString());
                bw.newLine();
            }
        }
    }
}
