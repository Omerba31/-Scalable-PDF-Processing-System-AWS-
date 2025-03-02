# Scalable PDF Processing System (AWS)

## Overview
This project is part of the **Distributed System Programming (DSP) course**, focusing on **scalable PDF processing** using a distributed computing approach. The system leverages **AWS cloud infrastructure**, including **SQS for message queuing, S3 for storage, and EC2 for computation**, while utilizing **Map-Reduce principles** to distribute workload efficiently.

## AWS Instance Configuration
- **AMI ID:** ami-054217b0faf130d36
- **Instance Type:** T2.nano

## System Components
The project consists of three primary components:
1. **Local Application** - Initiates processing, uploads PDF files, and retrieves results.
2. **Manager** - Manages job distribution and coordinates worker tasks.
3. **Worker Nodes** - Process PDFs and apply text extraction, OCR, and data analysis.

## Usage
```sh
java -jar LocalApp.jar <inputFileName1> ... <inputFileNameN> <outputFileName1> ... <outputFileNameN> <n> [terminate]
```
### Parameters:
- `inputFileNameX`: Name of input PDF files.
- `outputFileNameX`: Name of output files containing processed results.
- `n`: Number of jobs per worker.
- `[terminate]`: Optional flag to terminate the system after processing.

## System Workflow

### Local Application
- **Starts the Manager instance** if it is not already running.
- **Uploads input PDF files** to a unique **S3 bucket**.
- **Waits for the Manager** to initialize the `APP_TO_MANAGER` queue.
- **Sends a task request** containing metadata about the files and processing parameters.
- If the `terminate` flag is present, sends a termination message.
- **Waits for the Manager’s response**, downloads results from **S3**, and cleans up resources.

### Manager
- Initializes **three AWS SQS queues**:
  1. `APP_TO_MANAGER` - Receives tasks from Local Apps.
  2. `MANAGER_TO_WORKER` - Sends PDF processing tasks to Workers.
  3. `WORKER_TO_MANAGER` - Receives processed results from Workers.
- Uses **multi-threading** for concurrent task processing:
  - **Apps Listener**: Handles incoming requests, downloads input files, and distributes tasks.
  - **Workers Listener**: Collects results from workers, reconstructs files, and notifies local apps.
- **Dynamically scales** worker instances based on task load.
- **Handles termination requests** by shutting down workers and clearing resources.

### Worker Nodes
- **Poll tasks** from `MANAGER_TO_WORKER` queue.
- **Extracts text**, applies **OCR (if needed)**, and processes PDFs.
- **Sends results** back to the `WORKER_TO_MANAGER` queue.
- **Ensures message persistence**, deleting processed tasks only after confirmation.

## Security Considerations
- **Credential Management**: AWS credentials are stored **securely in a local directory**, ensuring they are not included in the source code or repository.
- **Restricted Access**: Credentials are **not exposed** in public environments, preventing unauthorized access.

## Scalability & Fault Tolerance
- The **Manager dynamically scales worker instances** based on task volume.
- **AWS limits student accounts** to **9 simultaneous instances** (including workers and the manager).
- A **custom ThreadPool implementation** optimizes task processing within the Manager instance.
- **Failure Recovery**:
  - If a worker node fails, tasks **remain in the queue** and are reassigned automatically after a visibility timeout (5 × n minutes).
  - The Manager ensures that messages **are not deleted** until processing is confirmed.
  - On system termination, the Manager **waits for all tasks to complete** before shutting down.

## Multi-Threading Strategy
- The **Manager utilizes a custom ThreadPool** for **parallel task execution**, optimizing resource utilization.
- Each task is processed in a **dedicated thread**, preventing bottlenecks.

## Termination Process
1. **Manager closes the APP_TO_MANAGER queue**.
2. **Waits for active tasks** (Apps Listener & Workers Listener) to finish processing.
3. **Shuts down all worker instances** and **cleans up SQS queues** (`MANAGER_TO_WORKER` & `WORKER_TO_MANAGER`).
4. **Self-terminates** after ensuring all tasks are completed.

