# TK-MLHUI — Top-k Multi-Level High Utility Itemset Mining

Java implementation of TK-MLHUI, built from scratch following a horizontal
(projection + merge) database architecture. Includes 3 ablation variants
used in the paper's experiments, selectable from the command line, so you
can compare execution time / memory / candidates / scanned lists between
them on the same dataset.

## Files

| File | Role |
|---|---|
| `Main.java` | Entry point: CLI parsing, dataset loading, output writers |
| `TKMLHUIAlgo.java` | The algorithm itself (TWU/exact-utility roll-up, Strategy 1–4, `Search()` recursion) |
| `Item.java` | Item record (id, name, taxonomy height, TWU, exact utility) |
| `Transaction.java` | A projected transaction record used during `Search()` |
| `Taxonomy.java` | Parses the taxonomy tree, computes item heights, generalizes leaves to a target level |
| `MemoryLogger.java` | Samples JVM heap usage to report peak memory |

## Build

Requires a JDK (11+; tested on 21).

```bash
cd src
javac *.java
```

## Input format

Point the program at a dataset directory containing:

- **`name.txt`** *(optional — only affects display names)*
  ```
  @ITEM=1=milk
  @ITEM=10=dairy
  ```
- **`taxonomy.txt`** *(required if the dataset is multi-level)* — one edge per line:
  ```
  child_id,parent_id
  ```
- **`utility.txt`** *(required)* — one transaction per line:
  ```
  item_id item_id ... : x : utility utility ...
  ```
  (the middle field is ignored; items and utilities are matched positionally)
- **`k.txt`** *(the normal way to set k)* — a single integer, the `k` in
  top-k, e.g. `input/fruithut/k.txt`. Read automatically whenever you don't
  pass `k` on the command line. Falls back to `10` only if neither is present.

## Usage

```
java -cp src Main <dataset_dir> [output_dir] [k] [--debug] [variant]
```

- `dataset_dir` — defaults to `input/sample`
- `output_dir` — defaults to `output`
- `k` — **optional, and normally you should leave it out**: it's read from
  `dataset_dir/k.txt` (i.e. `input/<tên data>/k.txt`) automatically. Only pass
  a `k` on the command line if you want to override what's in `k.txt` for one
  run. If neither is present, k falls back to `10`.
- `variant` — matched case-insensitively, `-`/`_` interchangeable:
  - `full` *(default)* — every optimization strategy enabled
  - `wo-all` — only core lu/su pruning; every other strategy disabled
  - `wo-merge` — everything except merge-before-mining
  - `all` — runs `full`, `wo-all`, and `wo-merge` one after another in a
    single invocation
- `--debug` — additionally writes a full step-by-step trace (heights, global
  TWU order, every `Search()` call's Sec/Pri, every candidate, every minU
  raise) to `output_debug_trace_<variant>.txt`

### Examples

```bash
mkdir -p out
javac -d out src/*.java
```

```bash
# normal use: k comes from input/fruithut/k.txt automatically
java -cp out Main input/fruithut output full
java -cp out Main input/fruithut output wo-all
java -cp out Main input/fruithut output wo-merge
java -cp out Main input/fruithut output all        # all 3 in one run
```

## The 3 variants

All three always use the core lu/su (Sec/Pri) pruning and "merge during
mining" (the per-`Search()`-call transaction merge) — that's the base search
architecture and is common to all of them. What differs:

| Strategy | full | wo-merge | wo-all |
|---|---|---|---|
| Pruning (lu/su, Sec/Pri) | ✅ | ✅ | ✅ |
| Update promising items during `Search()` (dynamic minU raise) | ✅ | ✅ | ❌ |
| Raise initial threshold from single-item + merged-transaction utilities | ✅ | ✅ | ❌ |
| Merge identical item-lists before mining starts | ✅ | ❌ | ❌ |

Because these are pure performance optimizations, **all three variants
produce the exact same set of top-k patterns** on a given dataset/k — they
only differ in execution time, peak memory, candidate count, and scanned
list count. (Patterns tied on utility may come out in a different order
between variants; that's expected, not a bug.)

## Output files

Written into `output_dir`:

| File | Written by | Contents |
|---|---|---|
| `output_topk_patterns_<variant>.txt` | every variant (own file each) | Dataset/k header, then rank/utility/pattern |
| `output_performance.txt` | every variant, **appended** (never overwritten) | One row per run — variant, k, dataset, execution time, peak memory, candidate count, scanned list count — so running `full`/`wo-all`/`wo-merge`/`all` builds up one shared comparison table |
| `output_mu_threshold_log.txt` | `full` only | Every minU threshold raise during the run, with the triggering pattern |
| `output_debug_trace_<variant>.txt` | only with `--debug` | Full step-by-step trace (capped at 20,000 lines) |

`output_performance.txt` is additive — delete it if you want a clean
comparison table for a fresh dataset/k, otherwise old rows just accumulate
underneath the new ones (each row is self-labeled with its own dataset and
k, so mixing runs won't corrupt older rows, just makes the file longer).
