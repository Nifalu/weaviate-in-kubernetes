# Weaviate Vector Import Script

This script imports vector data from BVECS format files into Weaviate using parallel batch processing.

## Usage

1. **Prerequisites**:

   - Weaviate cluster running and accessible
   - Vector data file in BVECS format
   - Go 1.24+ installed

2. **Configuration**:

   - Edit parameters in `main.go`

     ```go
         BATCH_SIZE  = 1000
         BUFFER_SIZE = 10000
         NUM_WORKERS = 8
         VECTOR_FILE = "../dataset/bigann_base.bvecs"
         CLASS_NAME  = "Vitrivr"
     ```

3. **Run the import**:

   ```bash
   go run main.go
   ```



## How it works:

1. **Reads** vectors from `../dataset/bigann_base.bvecs`

2. **Converts** byte values to float32 (0-255 → 0.0-255.0)

3. Creates

    Weaviate objects with:

   - Class: `Vitrivr`
   - Property: `type = "source"`
   - Vector: stored as `descriptor_averagecolor`

## Output

```bash
Starting import from ../dataset/bigann_base.bvecs with 8 workers, batch size 1000
Read 100000 vectors...
Progress: 50 batches, 50000 objects total, 0 errors, current: 5234.5 obj/sec, average: 5000.0 obj/sec
...
=== FINAL STATISTICS ===
Total time: 5m30s
Total objects imported: 1000000
Overall average rate: 3030.3 objects/sec
```