package main

import (
    "bufio"
    "context"
    "encoding/binary"
    "fmt"
    "io"
    "log"
    "os"
    "os/signal"
    "sync"
    "syscall"
    "time"

    "github.com/weaviate/weaviate-go-client/v5/weaviate"
    "github.com/weaviate/weaviate-go-client/v5/weaviate/grpc"
    "github.com/weaviate/weaviate/entities/models"
)

const (
    BATCH_SIZE  = 1000  // Objects per batch - tune this based on your Weaviate performance
    BUFFER_SIZE = 10000 // Channel buffer size
    NUM_WORKERS = 8     // Number of concurrent batch workers - adjust based on your system
    VECTOR_FILE = "../dataset/bigann_base.bvecs"
    CLASS_NAME  = "Vitrivr"
)

type VectorObject struct {
    ID     int
    Vector []float32
}

type BatchResult struct {
    BatchID int
    Success int
    Errors  int
}

func main() {
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()

    // Set up signal handling for graceful shutdown
    sigChan := make(chan os.Signal, 1)
    signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

    // Configure client for maximum performance
    cfg := weaviate.Config{
       Host:   "localhost:30080",
       Scheme: "http",
       GrpcConfig: &grpc.Config{
          Host:    "localhost:30051",
          Secured: false,
       },
    }

    client, err := weaviate.NewClient(cfg)
    if err != nil {
       log.Fatalf("Failed to create Weaviate client: %v", err)
    }

    log.Printf("Starting import from %s with %d workers, batch size %d",
       VECTOR_FILE, NUM_WORKERS, BATCH_SIZE)

    startTime := time.Now()

    // Channel for streaming vectors
    vectorChan := make(chan VectorObject, BUFFER_SIZE)
    resultChan := make(chan BatchResult, NUM_WORKERS*2)

    // Start vector reader goroutine
    go func() {
       defer close(vectorChan)
       if err := readBvecsFile(VECTOR_FILE, vectorChan, ctx); err != nil {
          log.Printf("Vector reading stopped: %v", err)
       }
    }()

    // Start batch workers
    var wg sync.WaitGroup
    for i := 0; i < NUM_WORKERS; i++ {
       wg.Add(1)
       go func(workerID int) {
          defer wg.Done()
          batchWorker(ctx, client, workerID, vectorChan, resultChan)
       }(i)
    }

    // Start result collector
    go func() {
       defer close(resultChan)
       wg.Wait()
    }()

    // Variables for tracking progress
    totalProcessed := 0
    totalErrors := 0
    batchCount := 0
    lastReportTime := startTime
    lastReportCount := 0

    // Function to print final statistics
    printFinalStats := func() {
       elapsed := time.Since(startTime)
       finalRate := float64(totalProcessed) / elapsed.Seconds()

       log.Printf("\n=== FINAL STATISTICS ===")
       log.Printf("Total time: %v", elapsed)
       log.Printf("Total batches processed: %d", batchCount)
       log.Printf("Total objects imported: %d", totalProcessed)
       log.Printf("Total errors: %d", totalErrors)
       log.Printf("Overall average rate: %.2f objects/sec", finalRate)
       if totalErrors > 0 {
          successRate := float64(totalProcessed) / float64(totalProcessed + totalErrors) * 100
          log.Printf("Success rate: %.2f%%", successRate)
       }
    }

    // Handle results and signals concurrently
    done := make(chan bool)
    
    go func() {
       defer func() { done <- true }()
       
       for result := range resultChan {
          totalProcessed += result.Success
          totalErrors += result.Errors
          batchCount++

          if batchCount%5 == 0 { // Report every 5 batches
             now := time.Now()
             elapsed := now.Sub(startTime)
             
             // Current rate since last report
             intervalDuration := now.Sub(lastReportTime)
             intervalProcessed := totalProcessed - lastReportCount
             currentRate := float64(intervalProcessed) / intervalDuration.Seconds()
             
             // Overall average rate
             overallRate := float64(totalProcessed) / elapsed.Seconds()

             log.Printf("Progress: %d batches, %d objects total, %d errors, current: %.2f obj/sec, average: %.2f obj/sec",
                batchCount, totalProcessed, totalErrors, currentRate, overallRate)
             
             // Update for next interval
             lastReportTime = now
             lastReportCount = totalProcessed
          }
       }
    }()

    // Wait for either completion or signal
    select {
    case <-done:
       log.Println("Import completed successfully!")
    case sig := <-sigChan:
       log.Printf("\nReceived signal %v, shutting down gracefully...", sig)
       cancel() // Cancel context to stop all workers
       
       // Wait a bit for workers to finish current batches
       select {
       case <-done:
          log.Println("Graceful shutdown completed")
       case <-time.After(10 * time.Second):
          log.Println("Shutdown timeout, forcing exit")
       }
    }

    printFinalStats()
}

// readBvecsFile reads vectors from bvecs format and streams them to the channel
func readBvecsFile(filename string, vectorChan chan<- VectorObject, ctx context.Context) error {
    file, err := os.Open(filename)
    if err != nil {
       return fmt.Errorf("failed to open file: %v", err)
    }
    defer file.Close()

    reader := bufio.NewReaderSize(file, 64*1024) // 64KB buffer for efficient reading
    vectorID := 0

    log.Println("Starting to read bvecs file...")

    for {
       // Check for cancellation
       select {
       case <-ctx.Done():
          log.Printf("Vector reading cancelled after %d vectors", vectorID)
          return ctx.Err()
       default:
       }

       // Read dimension (4 bytes, little endian)
       var dim uint32
       err := binary.Read(reader, binary.LittleEndian, &dim)
       if err == io.EOF {
          break
       }
       if err != nil {
          return fmt.Errorf("failed to read dimension: %v", err)
       }

       // Read vector data (dim bytes)
       vectorBytes := make([]byte, dim)
       _, err = io.ReadFull(reader, vectorBytes)
       if err != nil {
          return fmt.Errorf("failed to read vector data: %v", err)
       }

       // Convert bytes to float32
       vector := make([]float32, dim)
       for i, b := range vectorBytes {
          vector[i] = float32(b) // Convert byte (0-255) to float32
       }

       // Send to channel (this will block if channel is full, providing backpressure)
       select {
       case vectorChan <- VectorObject{ID: vectorID, Vector: vector}:
          vectorID++
       case <-ctx.Done():
          log.Printf("Vector reading cancelled after %d vectors", vectorID)
          return ctx.Err()
       }

       // Log progress occasionally
       if vectorID%100000 == 0 {
          log.Printf("Read %d vectors...", vectorID)
       }
    }

    log.Printf("Finished reading %d vectors from file", vectorID)
    return nil
}

// batchWorker processes vectors in batches
func batchWorker(ctx context.Context, client *weaviate.Client, workerID int,
    vectorChan <-chan VectorObject, resultChan chan<- BatchResult) {

    batchID := 0

    for {
       // Check for cancellation
       select {
       case <-ctx.Done():
          log.Printf("Worker %d cancelled after processing %d batches", workerID, batchID)
          return
       default:
       }

       // Collect vectors for a batch
       batch := make([]VectorObject, 0, BATCH_SIZE)

       // Fill batch or timeout
       batchTimeout := time.NewTimer(time.Second * 2) // Max wait time for batch to fill

    batchLoop:
       for len(batch) < BATCH_SIZE {
          select {
          case vector, ok := <-vectorChan:
             if !ok {
                // Channel closed, process remaining batch if any
                break batchLoop
             }
             batch = append(batch, vector)

          case <-batchTimeout.C:
             // Timeout reached, process current batch
             break batchLoop
             
          case <-ctx.Done():
             // Cancellation requested
             batchTimeout.Stop()
             break batchLoop
          }
       }

       batchTimeout.Stop()

       if len(batch) == 0 {
          break // No more vectors to process
       }

       // Process the batch
       result := processBatch(ctx, client, workerID, batchID, batch)
       
       select {
       case resultChan <- result:
       case <-ctx.Done():
          return
       }

       batchID++
    }

    log.Printf("Worker %d finished after processing %d batches", workerID, batchID)
}

// processBatch imports a batch of vectors to Weaviate
func processBatch(ctx context.Context, client *weaviate.Client, workerID, batchID int,
    vectors []VectorObject) BatchResult {

    batcher := client.Batch().ObjectsBatcher()

    for _, vector := range vectors {
       obj := &models.Object{
          Class: CLASS_NAME,
          Properties: map[string]interface{}{
             "type": "source", // Static value as requested
          },
          Vectors: map[string]models.Vector{
             "descriptor_averagecolor": vector.Vector,
          },
       }
       batcher.WithObjects(obj)
    }

    // Execute batch with timeout
    batchCtx, cancel := context.WithTimeout(ctx, 120*time.Second)
    defer cancel()

    results, err := batcher.Do(batchCtx)
    if err != nil {
       // Check if it was cancellation vs actual error
       if ctx.Err() != nil {
          log.Printf("Worker %d, Batch %d: Cancelled during processing", workerID, batchID)
          return BatchResult{BatchID: batchID, Success: 0, Errors: 0} // Don't count as errors
       }
       log.Printf("Worker %d, Batch %d: Failed to import batch: %v", workerID, batchID, err)
       return BatchResult{BatchID: batchID, Success: 0, Errors: len(vectors)}
    }

    // Count successes and errors
    success := 0
    errors := 0

    for i, result := range results {
       if result.Result.Errors != nil {
          errors++
          log.Printf("Worker %d, Batch %d, Object %d: Import error: %v",
             workerID, batchID, i, result.Result.Errors)
       } else {
          success++
       }
    }

    return BatchResult{
       BatchID: batchID,
       Success: success,
       Errors:  errors,
    }
}
