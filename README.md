# DSP Course - Sarcasm Analysis

## Authors

- **Shahaf Kita** (319000147)
- **Omer Ben Arie**
- **Chovav Wiengman**

## Overview

This project is part of the **Digital Signal Processing (DSP) course** and focuses on sarcasm analysis using a distributed system approach. The system is implemented in Java and runs on AWS infrastructure, utilizing SQS for message passing, S3 for data storage, and EC2 instances for computation.

## Instance Configuration

- **AMI ID:** ami-054217b0faf130d36
- **Instance Type:** T2.nano

## Implementation

The project consists of three main components:

1. **Local Application** - Initiates the process, uploads files, and handles responses.
2. **Manager** - Orchestrates tasks and distributes work to multiple workers.
3. **Worker Nodes** - Process the reviews and perform sarcasm analysis.

### Usage

```sh
java -jar LocalApp.jar <inputFileName1> ... <inputFileNameN> <outputFileName1> ... <outputFileNameN> <n> [terminate]
```

#### Parameters:

- `inputFileNameX`: Name of input text files containing lists of reviews.
- `outputFileNameX`: Name of output files for results.
- `n`: Number of jobs per worker.
- `[terminate]`: Optional flag to terminate the system after processing.

## System Architecture

### Local Application

- Starts a **Manager** instance if it doesn't exist.
- Creates a **unique S3 bucket** and uploads input files.
- Waits for the **Manager to open the SQS queue (APP\_TO\_MANAGER)**.
- Sends a message to the **Manager** containing task details.
- Waits for a response from the **Manager** indicating task completion.
- Downloads the output files from **S3** and deletes temporary resources.

### Manager

- Creates and manages **three SQS queues**:
  1. `APP_TO_MANAGER`: Receives tasks from Local Apps.
  2. `MANAGER_TO_WORKER`: Sends tasks to workers.
  3. `WORKER_TO_MANAGER`: Receives processed results from workers.
- Uses **two main threads** to manage tasks:
  - **Apps Listener**: Listens for incoming tasks, extracts data, and submits jobs to workers.
  - **Workers Listener**: Collects processed results, assembles them, and sends responses back to Local Apps.
- Dynamically **scales worker instances** based on task load.
- **Handles termination requests** by shutting down all workers and cleaning up resources.

### Worker Nodes

- Wait for tasks via the `MANAGER_TO_WORKER` queue.
- Process each review using **sentiment analysis and sarcasm detection**.
- Send results back to the **Manager** via the `WORKER_TO_MANAGER` queue.
- Remove processed messages after completing the job.

## Performance & Runtime Metrics

| Scenario                 | Number of Apps | Files per App    | Jobs per Worker (n) | Time Taken |
| ------------------------ | -------------- | ---------------- | ------------------- | ---------- |
| Single App (terminate)   | 1              | 5 (1577 KB)      | 10                  | 15 min     |
| Single App               | 1              | 5 (1577 KB)      | 30                  | 8 min      |
| Two Apps (1 terminate)   | 2              | 5 each (1557 KB) | 10                  | 20 min     |
| Two Apps                 | 2              | 5 each           | 10 & 20             | 17 min     |
| Three Apps (1 terminate) | 3              | 5 each           | 10                  | 25 min     |

## Security Measures

- **Credential Storage**: All AWS credentials are stored securely in a local folder, preventing unauthorized access.
- **Restricted Access**: Credentials are **not included** in the codebase or repository.

## Scalability Considerations

- The system **dynamically scales worker instances** based on task volume.
- AWS limitations restrict the **maximum instances to 9** (workers + manager).
- Uses a **ThreadPool** to optimize performance within the Manager instance.
- A **single Manager instance** is used to coordinate tasks.

## Fault Tolerance & Persistence

- **Worker Failure Handling**: If a worker crashes, tasks reappear in the queue after a visibility timeout (5 × n minutes).
- **Manager Persistence**: If a termination request is received, the manager waits for all pending tasks to finish before shutting down.
- **Ensured Data Integrity**: Messages are only deleted from queues once processing is confirmed.

## Thread Management

- The **Manager uses a custom ThreadPool** to handle multiple tasks efficiently.
- Each task runs as a **separate thread**, optimizing performance for handling multiple requests.

## Termination Process

1. The Manager **closes the APP\_TO\_MANAGER queue**.
2. Waits for **active threads** (Apps Listener & Workers Listener) to finish.
3. **Terminates all workers** and **closes remaining queues (MANAGER\_TO\_WORKER & WORKER\_TO\_MANAGER)**.
4. **Shuts itself down** after completing all tasks.

## Contact

For questions or contributions, please reach out to the project authors.

