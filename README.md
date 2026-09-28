# README — mlTKO, TK-MLHUI, U-TK-MLHUI

Ba cài đặt Java trong đồ án, theo đúng quan hệ kế thừa lý thuyết:

```
mlTKO  --(TK-MLHUI cải tiến hiệu năng, tái lập để đối chứng)-->  TK-MLHUI  --(mở rộng sang dữ liệu bất định)-->  U-TK-MLHUI
```

- **mlTKO** và **TK-MLHUI**: chạy trên dữ liệu chắc chắn (deterministic), dùng để
  tái lập + xác thực 2 bài báo nền (Mục 5.1, Mục 6.1 báo cáo).
- **U-TK-MLHUI**: thuật toán đề xuất của đồ án, mở rộng TK-MLHUI sang dữ liệu
  bất định với taxonomy là DAG đa-cha có trọng số (Định nghĩa 3.1, Chương 3),
  kế thừa nguyên vẹn kiến trúc tìm kiếm của TK-MLHUI (Chương 4).

Cả 3 đều dùng chung định dạng `output_performance.txt` (các dòng được nối
thêm, không ghi đè) — chạy cả 3 lần lượt rồi mở chung 1 file này là so sánh
được ngay.

---

## 1. mlTKO

Biên dịch:
```
javac -d out mltko/*.java
```

Chạy (mọi tham số tuỳ chọn, positional; không truyền `data_folder` sẽ hỏi tương tác):
```
java -cp out mltko.Main [data_folder] [k_file] [output_dir] [variant]
```

| Tham số | Ý nghĩa | Mặc định |
|---|---|---|
| `data_folder` | thư mục chứa `name.txt`, `taxonomy.txt`, `utility.txt` | hỏi tương tác |
| `k_file` | file chứa số nguyên k | `k.txt` → `<data_folder>/k.txt` → k=10 |
| `output_dir` | thư mục ghi kết quả | `output` |
| `variant` | `all` (chạy cả 3) hoặc đúng 1 tên biến thể | `all` |

Ví dụ:
```
java -cp out mltko.Main input/fruithut k.txt output all
java -cp out mltko.Main input/fruithut k.txt output mltko-nop
```

**Input**: `name.txt` (`@ITEM=id=ten_item`), `taxonomy.txt` (`child_id,parent_id`
— cây 1-cha, KHÔNG trọng số), `utility.txt` (`items : total_utility : utils`).

**3 biến thể ablation:**

| Biến thể | EUCP | Merge tối ưu | Ghi chú |
|---|---|---|---|
| `mltko` (đầy đủ) | bật | bật | baseline bài báo |
| `mltko-nop` | **tắt** | bật | ablation gốc của bài báo mlTKO (Mục 5 bài báo) |
| `mltko-wo-merge` | bật | **tắt** | biến thể bổ sung, KHÔNG có trong bài báo gốc |

Tắt EUCP/merge chỉ đổi hiệu năng, không được đổi tập Top-k cuối cùng —
`output_ablation_summary.txt` tự kiểm tra điều này khi chạy ≥2 biến thể.

**Output**: `output_topk_patterns_<variant>.txt`, `output_mu_threshold_log_<variant>.txt`
(1 file/biến thể), `output_performance.txt` (dùng chung 3 thuật toán),
`output_ablation_summary.txt`, `output_level_debug.txt`.

---

## 2. TK-MLHUI

Biên dịch:
```
javac -d out tkmlhui/*.java
```

Chạy (tham số vị trí và cờ có thể xen kẽ bất kỳ đâu):
```
java -cp out tkmlhui.Main <dataset_dir> [output_dir] [k] [--debug] [variant]
```

| Tham số | Ý nghĩa | Mặc định |
|---|---|---|
| `dataset_dir` | thư mục chứa `name.txt`, `taxonomy.txt`, `utility.txt`, `k.txt` (tuỳ chọn) | `input/sample` |
| `output_dir` | thư mục ghi kết quả | `output` |
| `k` | số nguyên top-k | đọc `k.txt` trong `dataset_dir`, mặc định 10 |
| `variant` | `full` \| `wo-all` \| `wo-merge` \| `all` | `full` |
| `--debug` | ghi thêm file trace chi tiết | tắt |

Ví dụ:
```
java -cp out tkmlhui.Main input/fruithut output 100 all
java -cp out tkmlhui.Main input/fruithut output 100 --debug wo-merge
```

**Input**: giống hệt mlTKO (`name.txt`, `taxonomy.txt` 1-cha không trọng số,
`utility.txt`).

**3 biến thể (code hỗ trợ, đồ án chỉ dùng 2/3 — Mục 5.1 báo cáo):**

| Biến thể | So với bản đầy đủ | Ghi chú |
|---|---|---|
| `full` | — | Strategy 1+2+3+4 đều bật |
| `wo-merge` | tắt Strategy 4 (gộp giao dịch) | **có dùng** trong đồ án |
| `wo-all` | tắt Strategy 2+3+4, chỉ giữ Strategy 1 | **KHÔNG dùng** trong đồ án |

**Output**: `output_topk_patterns_tkmlhui_<variant>.txt`, `output_performance.txt`
(dùng chung 3 thuật toán), `output_mu_threshold_log_tkmlhui.txt` (chỉ khi
chạy `full`), `output_debug_trace_<variant>.txt` (chỉ khi có `--debug`).

---

## 3. U-TK-MLHUI

Biên dịch:
```
javac -d out utkmlhui/*.java
```

Chạy:
```
java -cp out utkmlhui.Main <dataset_dir> [output_dir] [k] [--debug] [variant] [--min-size N] [prob_file.txt]
```

| Tham số | Ý nghĩa | Mặc định |
|---|---|---|
| `dataset_dir` | thư mục dữ liệu (định dạng bên dưới) | `input/sample` |
| `output_dir` | thư mục ghi kết quả | `output` |
| `k` | số nguyên top-k | đọc `k.txt`, mặc định 10 |
| `variant` | `full` \| `wo-elu` \| `wo-esu` \| `wo-merge` \| `wo-threshold-raising` \| `all` | `full` |
| `--debug` | ghi thêm file trace chi tiết | tắt |
| `--min-size N` | kích thước itemset tối thiểu vào top-k | **2** (xem lý do bên dưới) |
| `prob_file.txt` | (định dạng input cũ) tên file probability khác mặc định | `probability.txt` |

Ví dụ:
```
java -cp out utkmlhui.Main input/iwslt15 output 100 all
java -cp out utkmlhui.Main input/iwslt15 output 100 --debug wo-elu
java -cp out utkmlhui.Main input/toy_data output 5 full --min-size 1   # test, tắt lọc size-1
```

**Input**:
- `name.txt`: `@ITEM=id=ten_item` (leaf/variant/lemma dùng chung định dạng)
- `taxonomy.txt`: `child_id,parent_id,weight` — **DAG đa-cha có trọng số**
  (Định nghĩa 3.1): 1 `child_id` có thể lặp lại nhiều dòng (mỗi dòng 1 cha),
  đây là thiết kế có chủ đích. Dòng 2 cột (không weight) vẫn nhận, mặc định
  weight=1.0, để tương thích ngược.
- `utility_probability.txt` (định dạng pipeline hiện tại, **ưu tiên nếu có**):
  `items : TWU : utilities : probabilities`, chỉ liệt kê leaf. Cột probabilities
  hiện luôn =1.0 vì bất định đã dời sang trọng số taxonomy (Định nghĩa 3.2).
- `utility.txt` + `probability.txt` (định dạng cũ, dùng khi không có file gộp ở trên).

**4 biến thể ablation A–D (Mục 5.3 báo cáo):**

| Biến thể | So với bản đầy đủ | Đo đóng góp của |
|---|---|---|
| `full` (A) | — | — |
| `wo-elu` (B) | tắt cận Elu | Elu |
| `wo-esu` (C) | tắt cận Esu | Esu |
| `wo-merge` (D) | tắt gộp giao dịch trước khai thác | Strategy merge |
| `wo-threshold-raising` (E) | tắt cả Strategy 1&2 lẫn Strategy 3 | có trong code, **KHÔNG dùng** trên dữ liệu thật vì quá chậm |

**Vì sao mặc định `--min-size 2`**: với dữ liệu ngôn ngữ, itemset 1 phần tử
chỉ là 1 từ đơn lẻ, không mang ý nghĩa "đồng xuất hiện" nên bị loại khỏi
top-k. Việc lọc diễn ra ngay trong tìm kiếm (ảnh hưởng cả cách tính `minU`
khởi tạo), không phải lọc sau ở output — xem `--min-size 1` để khôi phục
hành vi không giới hạn (dùng khi test).

**Output**: `output_topk_patterns_utkmlhui_<variant>.txt`, `output_performance.txt`
(dùng chung 3 thuật toán), `output_mu_threshold_log_utkmlhui.txt` (chỉ khi
chạy `full`), `output_debug_trace_<variant>.txt` (chỉ khi có `--debug`).

**Liên quan trong báo cáo**: Định nghĩa 3.1–3.4 (Chương 3, cài trong
`Taxonomy.computeTransactionEU()`), Bổ đề A mở rộng + Property 4′–8′
(Chương 4, cài trong `UTKMLHUIAlgo.search()`), Ví dụ 3.1–3.2 và ví dụ chạy
Top-k từng bước (Mục 4.7), bộ dữ liệu thật + kết quả ablation (Mục 5.2–5.3,
Chương 6).
