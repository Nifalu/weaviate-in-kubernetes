package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"os"
	"sync"
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
	Error   error
}

func main() {
	// Create a cancellable context
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

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
		if err := readBvecsFile(ctx, VECTOR_FILE, vectorChan); err != nil {
			if ctx.Err() == nil { // Only log if not cancelled
				log.Printf("Failed to read bvecs file: %v", err)
				cancel() // Cancel context to stop all workers
			}
		}
	}()

	// Start batch workers
	var wg sync.WaitGroup
	for i := 0; i < NUM_WORKERS; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			batchWorker(ctx, cancel, client, workerID, vectorChan, resultChan)
		}(i)
	}

	// Start result collector
	go func() {
		defer close(resultChan)
		wg.Wait()
	}()

	// Collect and report results
	totalProcessed := 0
	totalErrors := 0
	batchCount := 0
	var firstError error

	for result := range resultChan {
		if result.Error != nil && firstError == nil {
			firstError = result.Error
			cancel() // Stop all processing
		}

		totalProcessed += result.Success
		totalErrors += result.Errors
		batchCount++

		if batchCount%5 == 0 { // Report every 5 badges
			elapsed := time.Since(startTime)
			rate := float64(totalProcessed) / elapsed.Seconds()

			log.Printf("Progress: %d batches, %d objects imported, %d errors, %.2f objects/sec",
				batchCount, totalProcessed, totalErrors, rate)
		}
	}

	elapsed := time.Since(startTime)
	finalRate := float64(totalProcessed) / elapsed.Seconds()

	if firstError != nil {
		log.Printf("Import stopped due to error: %v", firstError)
	} else {
		log.Printf("Import completed!")
	}
	log.Printf("Total time: %v", elapsed)
	log.Printf("Total objects: %d", totalProcessed)
	log.Printf("Total errors: %d", totalErrors)
	log.Printf("Average rate: %.2f objects/sec", finalRate)

	if firstError != nil {
		os.Exit(1)
	}
}

// readBvecsFile reads vectors from bvecs format and streams them to the channel
func readBvecsFile(ctx context.Context, filename string, vectorChan chan<- VectorObject) error {
	file, err := os.Open(filename)
	if err != nil {
		return fmt.Errorf("failed to open file: %v", err)
	}
	defer file.Close()

	reader := bufio.NewReaderSize(file, 64*1024) // 64KB buffer for efficient reading
	vectorID := 0

	log.Println("Starting to read bvecs file...")

	for {
		// Check if context is cancelled
		select {
		case <-ctx.Done():
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
		case vectorChan <- VectorObject{
			ID:     vectorID,
			Vector: vector,
		}:
		case <-ctx.Done():
			return ctx.Err()
		}

		vectorID++

		// Log progress occasionally
		if vectorID%100000 == 0 {
			log.Printf("Read %d vectors...", vectorID)
		}
	}

	log.Printf("Finished reading %d vectors from file", vectorID)
	return nil
}

// batchWorker processes vectors in batches
func batchWorker(ctx context.Context, cancel context.CancelFunc, client *weaviate.Client, workerID int,
	vectorChan <-chan VectorObject, resultChan chan<- BatchResult) {

	batchID := 0

	for {
		// Check if context is cancelled
		select {
		case <-ctx.Done():
			return
		default:
		}

		// Collect vectors for a batch
		batch := make([]VectorObject, 0, BATCH_SIZE)

		// Fill batch or timeout
		batchTimeout := time.NewTimer(time.Second) // Max wait time for batch to fill

	batchLoop:
		for len(batch) < BATCH_SIZE {
			select {
			case <-ctx.Done():
				batchTimeout.Stop()
				return

			case vector, ok := <-vectorChan:
				if !ok {
					// Channel closed, process remaining batch if any
					break batchLoop
				}
				batch = append(batch, vector)

			case <-batchTimeout.C:
				// Timeout reached, process current batch
				break batchLoop
			}
		}

		batchTimeout.Stop()

		if len(batch) == 0 {
			break // No more vectors to process
		}

		// Process the batch
		result := processBatch(ctx, client, workerID, batchID, batch)
		
		// Send result
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
	batchCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	results, err := batcher.Do(batchCtx)
	if err != nil {
		return BatchResult{
			BatchID: batchID,
			Success: 0,
			Errors:  len(vectors),
			Error:   fmt.Errorf("Worker %d, Batch %d: Failed to import batch: %v", workerID, batchID, err),
		}
	}

	// Count successes and errors
	success := 0
	errors := 0

	for i, result := range results {
		if result.Result.Errors != nil {
			errors++
			// Return immediately on first error in batch
			return BatchResult{
				BatchID: batchID,
				Success: success,
				Errors:  errors,
				Error:   fmt.Errorf("Worker %d, Batch %d, Object %d: Import error: %v", workerID, batchID, i, result.Result.Errors),
			}
		} else {
			success++
		}
	}

	return BatchResult{
		BatchID: batchID,
		Success: success,
		Errors:  errors,
		Error:   nil,
	}
}
