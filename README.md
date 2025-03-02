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

![image](https://github.com/user-attachments/assets/f1c1ec93-5b69-4805-8ca8-ee11ba41c949)

1. Local Application uploads the file with the list of PDF files and operations to S3.
2. Local Application sends a message (queue) stating the location of the input file on S3.
3. Local Application does one of the two:
4. Starts the manager.
5. Checks if a manager is active and if not, starts it.
4. Manager downloads list of PDF files together with the operations.
5. Manager creates an SQS message for each URL and operation from the input list.
6. Manager bootstraps nodes to process messages.
7. Worker gets a message from an SQS queue.
8. Worker downloads the PDF file indicated in the message.
9. Worker performs the requested operation on the PDF file, and uploads the resulting output
to S3.
10. Worker puts a message in an SQS queue indicating the original URL of the PDF file and the S3
URL of the output file, together with the operation that produced it.
11. Manager reads all Workers' messages from SQS and creates one summary file, once all URLs
in the input file have been processed.
12. Manager uploads the summary file to S3.
13. Manager posts an SQS message about the summary file.
14. Local Application reads SQS message.
15. Local Application downloads the summary file from S3.
16. Local Application creates html output file.
17. Local application send a terminate message to the manager if it received terminate as one of
its arguments
