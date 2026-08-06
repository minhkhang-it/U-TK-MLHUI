# mlTKO — Top-K Multi-Level High Utility Pattern Mining (Java)

> **Bản rút gọn**: chỉ chạy thuật toán **mlTKO đầy đủ** (EUCP + merge
> optimization đều bật). Các biến thể ablation trước đây (`mlTKO-nop`,
> `mlTKO-wo-merge`) đã bị gỡ bỏ khỏi `Main.java` — chương trình luôn chạy
> đúng 1 lần, đúng cấu hình paper gốc, không còn tham số chọn biến thể, và
> **không in bất kỳ log/kết quả nào ra terminal** — toàn bộ kết quả chỉ
> được ghi ra các file trong `output_dir` (xem mục 4 và 5 bên dưới).

Cài đặt Java 8+ (build/test trên JDK 21) cho thuật toán **mlTKO**, bám sát
trực tiếp 4 paper gốc:

1. **Le, Nguyen, Nguyen, Kozierkiewicz, Tung** — *"Extracting Top-k High
   Utility Patterns from Multi-level Transaction Databases"* (ACIIDS 2023,
   paper chính [16]) — định nghĩa toán học, chiến lược initial-mu (Sec 4.1),
   ràng buộc "cùng level" của multi-level pattern (Sec 3.1).
2. **Liu & Qu** — *"Mining High Utility Itemsets without Candidate
   Generation"* (HUI-Miner, CIKM 2012) — cấu trúc Utility-List, thuật toán
   CONSTRUCT (Algorithm 1).
3. **Fournier-Viger et al.** — *"FHM: Faster High-Utility Itemset Mining
   using Estimated Utility Co-occurrence Pruning"* — cấu trúc EUCS, chiến
   lược cắt tỉa EUCP (Algorithm 2/3).
4. **Tseng, Wu, Fournier-Viger, Yu** — *"Efficient Algorithms for Mining
   Top-K High Utility Itemsets"* (TKO/TKU, TKDE 2016) — chiến lược RUC
   (Raising the threshold by Utility of Candidates), khung one-phase search
   mà mlTKO mở rộng.

## 1. Cấu trúc project

```
src/mltko/
  Element.java              tuple <tid, iutil, rutil>
  UtilityList.java           danh sách Element của 1 pattern + sumIutil/sumRutil
  Pattern.java                itemset + utility
  Transaction.java            1 giao dịch đã parse
  TaxonomyTree.java           cây phân cấp: parent/child, levelOf (longest-path), isAncestorOrDescendant
  EUCSMatrix.java              ma trận đồng-xuất-hiện (bit-packed long key), theo FHM
  TopKManager.java            min-heap Top-K + RUC threshold raising, theo TKO paper
  MuThresholdLog.java          ghi lại lịch sử mu từ initial -> mọi lần RUC raise
  DataLoader.java              đọc name.txt / taxonomy.txt / utility.txt (tên file tổng quát,
                                 xem mục 2 -- vẫn nhận tên FruitHut cũ nếu có)
  MlTKOAlgorithm.java          lõi thuật toán, đúng theo 4 paper (Sec 3, 4)
  PerformanceMetrics.java      gói chỉ số hiệu năng của 1 lần chạy (kèm dataset + k)
  ResultWriter.java             ghi 4 file output riêng biệt — chỉ ghi ra file, KHÔNG in ra
                                 terminal (xem mục 5)
  SampleDataGenerator.java     sinh dữ liệu demo khi thư mục input thiếu/không có
  Main.java                    hỏi/nhận tên thư mục input, đọc k.txt, chạy mlTKO, ghi output
```

`input/` (mỗi bộ dữ liệu 1 thư mục con) và `output/` (nơi ghi kết quả) không
đi kèm trong bản zip này — tự tạo lại khi cần chạy thực tế (xem mục 2 và 3).

## 2. Định dạng dữ liệu input (áp dụng cho MỌI bộ dữ liệu)

Mỗi bộ dữ liệu là **một thư mục riêng** đặt trong `input/`, chứa đúng 3 file
tên tổng quát sau (không còn gắn cứng theo tên "fruithut" nữa):

- `name.txt` — dòng dạng `@ITEM=id=ten_item`
- `taxonomy.txt` — dòng dạng `child_id,parent_id`
- `utility.txt` — dòng dạng `item1 item2 ... : tong_utility : util1 util2 ...`
- `k.txt` (tuỳ chọn, đặt trong cùng thư mục) — chỉ chứa số nguyên k

Muốn chạy thêm bộ dữ liệu mới: tạo 1 thư mục con mới trong `input/` (ví dụ
`input/ten_bo_du_lieu_moi/`), copy 3 file trên vào, rồi chạy chương trình —
không cần sửa code.

> Tên file kiểu FruitHut cũ (`item_name.txt`, `Fruithut_taxonomy_data.txt`,
> `fruithut_utility.txt`) vẫn được nhận diện tự động nếu bộ dữ liệu cũ chưa
> đổi tên, để không phải sửa lại các bộ dữ liệu đã có sẵn.

## 3. Biên dịch & chạy

```bash
tại thư mục gốc project:
javac -d out src/mltko/*.java
java -cp out mltko.Main [data_folder] [k_file] [output_dir]

vd: java -cp out mltko.Main input/toy k.txt output/
    java -cp out mltko.Main input/fruithut k.txt output/
    java -cp out mltko.Main input/accidents k.txt output/
```

- `data_folder`: thư mục chứa `name.txt` / `taxonomy.txt` / `utility.txt`
  của **một** bộ dữ liệu (ví dụ `input/fruithut`). **Nếu bỏ trống tham số
  này**, chương trình sẽ hỏi trực tiếp trên terminal — liệt kê các thư mục
  con có sẵn trong `input/` để bạn chọn nhanh bằng cách gõ đúng tên (ví dụ
  gõ `fruithut`), hoặc gõ hẳn đường dẫn khác nếu bộ dữ liệu nằm ngoài
  `input/`. Đây là bước nhập liệu **duy nhất** còn tương tác qua terminal;
  mọi log/kết quả khác đều chỉ được ghi ra file.
- `k_file`: file văn bản chỉ chứa số k, mặc định tìm `k.txt` ở thư mục hiện
  tại, rồi `<data_folder>/k.txt`, nếu không có file nào thì mặc định k=10.
- `output_dir`: mặc định `output/`.

**Quan trọng:** nếu thư mục dữ liệu chỉ định không tồn tại/thiếu file,
chương trình tự sinh dữ liệu demo nhỏ ngay trong thư mục đó để chạy thử
end-to-end (không có cảnh báo trên terminal — chương trình không in gì ra
terminal, cứ mở file output lên để biết kết quả). Copy dữ liệu thật vào
thư mục input tương ứng để có kết quả thật.

## 4. Không in ra terminal — chỉ ghi file

Chương trình **không in bất kỳ log/kết quả nào ra terminal** (không còn
`[INFO]`, `[WARN]`, `[DIAG]`, không echo lại nội dung file ra console).
Toàn bộ kết quả chỉ nằm trong 4 file bên trong `output_dir`. Ngoại lệ duy
nhất: nếu không truyền `data_folder` trên dòng lệnh, chương trình vẫn cần
hỏi tên thư mục input qua terminal (không thể tránh, vì đó là đầu vào cần
thiết để chạy được).

## 5. Output — 4 file riêng biệt

- **`output_topk_patterns.txt`**: Top-K MLHUP của mlTKO. Định dạng cột,
  phân cách bằng tab:

  ```
  Rank	Utility	Pattern (id:name)
  1	71	100:Category-100, 200:Category-200, 300:Category-300
  2	62	100:Category-100, 300:Category-300
  ...
  ```

- **`output_performance.txt`**: bảng chỉ số hiệu năng của lần chạy mlTKO
  (1 dòng dữ liệu duy nhất, vì chỉ còn 1 biến thể). Định dạng cột:

  ```
  Variant              | k      | Dataset              | Execution Time      | Peak Memory    | Candidates   | Scanned Lists
  mltko                | 5      | toy                  | 27 ms               | 3.79 MB        | 30           | 21
  ```

- **`output_mu_threshold_log.txt`**: lịch sử đầy đủ của ngưỡng mu từ giá
  trị khởi tạo (Sec 4.1) cho tới lần RUC raise cuối cùng, mỗi dòng là 1 lần
  mu tăng: chỉ số candidate tại thời điểm đó, mu cũ → mu mới, và pattern
  nào đã kích hoạt việc raise ngưỡng. Dòng đầu file luôn ghi rõ đang chạy
  bộ dữ liệu nào và k = bao nhiêu, ví dụ: `Dataset: toy | k = 5`.

- **`output_level_debug.txt`**: bảng debug level(min)/shortest-path vs
  level(max)/longest-path cho từng category trong taxonomy. Dòng đầu file
  cũng ghi rõ `Dataset: <ten> | k = <so>` giống như file mu-threshold-log.

## 6. Giới hạn / ghi chú kỹ thuật

- Peak memory đo bằng `MemoryPoolMXBean.getPeakUsage()` (API JMX chuẩn của
  JVM, reset trước mỗi lần chạy) — chính xác hơn nhiều so với chụp snapshot
  `Runtime.totalMemory()-freeMemory()` trước/sau.
- ID namespace: giả định id leaf item và id node taxonomy không trùng nhau.
- File `k.txt` chỉ cần chứa 1 số nguyên (regex tự trích số đầu tiên tìm
  thấy trong file, nên `k=10` hay `10` đều đọc được).
- Cột/dòng `Dataset` trong `output_performance.txt` và phần header của các
  file còn lại lấy trực tiếp từ tên thư mục `data_folder` (ví dụ
  `input/fruithut` → `fruithut`).
