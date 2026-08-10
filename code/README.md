# mlTKO & TK-MLHUI

Hai cài đặt Java độc lập nằm chung một project, dùng chung định dạng
input/output để tiện so sánh kết quả trên cùng một bộ dữ liệu:

```
src/mltko/     package mltko    -- thuật toán mlTKO
src/tkmlhui/   package tkmlhui  -- thuật toán TK-MLHUI (3 biến thể ablation)
input/<name>/  bộ dữ liệu dùng chung cho cả hai thuật toán
output/mltko/, output/tkmlhui/  thư mục output gợi ý cho từng thuật toán
```

Cả hai đọc chung 1 bộ input (`name.txt`, `taxonomy.txt`, `utility.txt`,
`k.txt`) và ghi ra file `output_*.txt` cùng quy ước đặt tên/định dạng bảng,
để log của 2 thuật toán trên cùng dataset có thể đối chiếu trực tiếp.

## Build

Linux / macOS / Git Bash / WSL:
```bash
javac -d bin $(find src -name "*.java")
```

Windows PowerShell (`find`/`$()` không có sẵn, dùng cách này thay thế):
```powershell
javac -d bin -encoding UTF-8 src\mltko\*.java src\tkmlhui\*.java
```

## Chạy: gọi tên thuật toán (package) + tên bộ dữ liệu (folder trong input/)

```bash
# mlTKO
java -cp bin mltko.Main input/<dataset>

# TK-MLHUI (biến thể full mặc định)
java -cp bin tkmlhui.Main input/<dataset>
```

Ví dụ chạy cùng 1 bộ dữ liệu `chess` trên cả hai thuật toán, ghi chung vào
một thư mục `output/` để tiện so sánh (tên file đã tự phân biệt theo thuật
toán, xem phần Output bên dưới):

```bash
java -cp bin mltko.Main   input/chess output
java -cp bin tkmlhui.Main input/chess output
```

## Input dùng chung (`input/<dataset>/`)

| File            | Nội dung |
|-----------------|----------|
| `name.txt`      | `@ITEM=<id>=<ten_item>` mỗi dòng một item |
| `taxonomy.txt`  | `<child_id>,<parent_id>` mỗi dòng một cạnh cây phân cấp |
| `utility.txt`   | `<item_ids> : <TU giao dịch> : <utility từng item>` mỗi dòng một giao dịch |
| `k.txt`         | số nguyên k (top-k); nếu thiếu, cả hai chương trình mặc định k=10 |

## Output dùng chung — cùng định dạng, nhưng tên file luôn có tên thuật toán để không đè lẫn nhau

Có thể trỏ `output_dir` của cả hai thuật toán vào **cùng một thư mục**
(ví dụ chung `output/`) mà không sợ file của thuật toán này ghi đè file của
thuật toán kia — trừ `output_performance.txt` là **cố ý** dùng chung để gộp
kết quả so sánh (xem bên dưới).

- **`output_topk_patterns_mltko.txt`** (mlTKO) /
  **`output_topk_patterns_tkmlhui_<variant>.txt`**
  (TK-MLHUI, `<variant>` = `full` | `wo_all` | `wo_merge`):
  dòng đầu `Dataset: ... | k = ...`, dòng header `Rank\tUtility\tPattern (id:name)`,
  mỗi pattern in dạng `{id:name, id:name, ...}`; nếu không tìm được pattern nào
  thì in `(no pattern found)`. Đây là 2 file **riêng biệt theo tên thuật toán**
  để bạn mở song song và so sánh trực tiếp top-k của mlTKO với TK-MLHUI trên
  cùng một dataset.

- **`output_performance.txt`**: bảng căn cột
  `Variant | k | Dataset | Execution Time | Peak Memory | Candidates | Scanned Lists`.
  File này **dùng chung tên** và được **ghi thêm (append)** qua từng lần chạy,
  không bị ghi đè — chạy cả mlTKO và TK-MLHUI vào cùng một `output_dir` sẽ cho
  một bảng so sánh performance duy nhất, xếp theo `Variant` (giá trị `mlTKO`
  hoặc `full`/`wo_all`/`wo_merge`).

- **`output_mu_threshold_log_mltko.txt`** / **`output_mu_threshold_log_tkmlhui.txt`**:
  dòng đầu `Dataset: ... | k = ...`, sau đó mỗi lần ngưỡng mu (minimum utility)
  được nâng lên là một dòng tự mô tả:
  ```
  INIT | candidates=0 | minU: (none) -> <mu0> | trigger=<lý do khởi tạo>
  candidates=<n> | minU: <old> -> <new> | trigger=<pattern gây nâng ngưỡng>
  ```
  (TK-MLHUI chỉ ghi file này cho biến thể `full`.)

## Chạy các biến thể ablation của TK-MLHUI

Cú pháp đầy đủ:
```
java -cp bin tkmlhui.Main <dataset_dir> [output_dir] [k] [--debug] [variant]
```
`variant` gõ ở vị trí nào trong tham số cũng được, không phân biệt hoa/thường,
`-` và `_` dùng thay nhau được:

| variant | Ý nghĩa |
|---|---|
| `full` (mặc định, không cần gõ) | TK-MLHUI đầy đủ — bật cả 3 tối ưu |
| `wo-all` | tắt hết 3 tối ưu (baseline) |
| `wo-merge` | chỉ tắt tối ưu merge trước khi khai phá |
| `all` | chạy cả 3 biến thể trên, liên tiếp trong 1 lần gọi |

Ví dụ trên dataset `chess`, output chung vào `output/`:
```bash
java -cp bin tkmlhui.Main input/chess output              # full (mặc định)
java -cp bin tkmlhui.Main input/chess output 5 wo-all      # wo-all
java -cp bin tkmlhui.Main input/chess output 5 wo-merge    # wo-merge
java -cp bin tkmlhui.Main input/chess output 5 all         # cả 3 biến thể 1 lần
```

Ghi chú:
- Tham số `k` là optional — nếu bỏ qua, chương trình đọc `k.txt` trong
  `input/<dataset>/`, mặc định 10 nếu không có.
- Mỗi biến thể ghi ra file top-k riêng: `output_topk_patterns_tkmlhui_full.txt`,
  `..._wo_all.txt`, `..._wo_merge.txt`.
- Cả 3 biến thể append chung vào `output_performance.txt`, nên chạy `all`
  (hoặc chạy riêng từng biến thể) cho ra 3 dòng để so sánh trực tiếp
  full / wo-all / wo-merge với nhau.
- `output_mu_threshold_log_tkmlhui.txt` chỉ được ghi bởi biến thể `full`.
- Thêm `--debug` để ghi trace chi tiết từng bước Search() vào
  `output_debug_trace_<variant>.txt`, ví dụ:
  `java -cp bin tkmlhui.Main input/chess output 5 wo-all --debug`.

## Riêng của từng thuật toán

- **mlTKO** chỉ có một biến thể (bản đầy đủ, EUCP + merge); các biến thể
  ablation cũ đã bị bỏ khỏi bản build này. mlTKO còn ghi thêm
  `output_level_debug.txt` (chỉ để debug, không có bên TK-MLHUI): so sánh
  `levelOf` (longest-path, thuật toán đang dùng) với `levelOfShortest`
  (shortest-path, theo cách đọc nghĩa đen của Định nghĩa 2 trong paper) cho
  từng category trong taxonomy.

- **TK-MLHUI** hỗ trợ chọn biến thể ablation qua tham số dòng lệnh và cờ
  `--debug` — xem mục "Chạy các biến thể ablation của TK-MLHUI" ở trên.

## Ghi chú merge

- Khi merge 2 project cũ vào 1 chỗ, các file trong `src/tkmlhui/` bị thiếu
  khai báo `package tkmlhui;` (đang ở default package) — đã thêm lại để khớp
  với `package mltko;` bên kia, cho phép gọi thống nhất bằng
  `java -cp bin <package>.Main ...`.
- `mltko/Main.java` có một dòng log terminal ghi nhầm "Running TK-MLHUI..."
  (chắc do copy code lúc merge) — đã sửa lại thành "Running mlTKO...".
