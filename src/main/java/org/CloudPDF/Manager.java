package org.CloudPDF;

import software.amazon.awssdk.services.sqs.model.Message;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Manager extends AWS {
    private final AtomicReference<Integer> globalPacketsCounter = new AtomicReference<>(0);
    private final AtomicBoolean isTerminating = new AtomicBoolean(false);
    protected final Object lock = new Object();
    private int workerCount = 0;
    private final int tasksPerWorker;
    private final ConcurrentHashMap<String, Integer> clientMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> clientDoneMessages = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutorService = Executors.newFixedThreadPool(10);
    private final ExecutorService clientExecutorService = Executors.newFixedThreadPool(10);
    private final CopyOnWriteArrayList<String> workersList = new CopyOnWriteArrayList<>();

    String WorkerScript = generateWorkerScript();

    public Manager(int tasksPerWorker) {
        this.tasksPerWorker = tasksPerWorker;
        connectAWS();
        System.out.println("Manager is initialized...");
    }

    public Manager(int tasksPerWorker, boolean debug) {
        this.tasksPerWorker = tasksPerWorker;
        if (!debug) {
            connectAWS();
        }
    }

    public void run() {
        System.out.println("org.CloudPDF.Manager is running...");

        Thread workerListenerThread = new Thread(this::readMessagesFromWorkers);
        workerListenerThread.start();
        while (!getIsTerminating()) {
            Message clientMessage = receiveMessageFromQueue(client2managerUrl);
            if (clientMessage != null) {
                System.out.println("Received message");
                clientExecutorService.submit(() -> handleClientMessage(clientMessage));
            }
        }
        try {
            workerListenerThread.join();
            System.out.println("org.CloudPDF.Manager is shut down");
            terminateManagerInstance();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private String generateWorkerScript() {
        String JAR_LOCAL_DIR = "/home/ec2-user/WorkerFiles";
        String JAR_LOCAL_PATH = "/home/ec2-user/WorkerFiles/fatWorker.jar";
        String JAR_S3_PATH = "jars/fatWorker.jar";
        String S3_BUCKET_NAME = bucketName;
        String REGION = "us-west-2";

        return "#!/bin/bash\n" +
                "exec > >(tee /var/log/worker-script.log | logger -t worker-script -s 2>/dev/console) 2>&1\n" +
                "mkdir -p " + JAR_LOCAL_DIR + "\n" +
                "aws s3 cp s3://" + S3_BUCKET_NAME + "/" + JAR_S3_PATH + " " + JAR_LOCAL_PATH + " --region " + REGION + "\n" +
                "java -jar " + JAR_LOCAL_PATH + "\n";
    }

    private void readMessagesFromWorkers() {
        while ((getGlobalPacketsCounter() > 0) || (getGlobalPacketsCounter() == 0 && !getIsTerminating())) {
            Message workerMessage = receiveMessageFromQueue(workers2managerUrl, 5);
            if (workerMessage != null) {
                workerExecutorService.submit(() -> handleWorkerMessage(workerMessage));
            }
        }
    }

    private void handleWorkerMessage(Message workerMessage) {
        String clientID = getClientIdFromMessage(workerMessage);
        appendReturnMessage(clientID, workerMessage);
        clientMessages.put(clientID, clientMessages.get(clientID) - 1);

        if (clientMessages.get(clientID) == 0) {
            handleClientCompletion(clientID);
        }

        deleteMessageFromQueue(workers2managerUrl, workerMessage);
        decrementGlobalPacketsCounter();
        syncWorkerCount();
    }

    private void appendReturnMessage(String clientID, Message message) {
        StringBuilder returnMessage;
        if (clientDoneMessages.containsKey(clientID)) {
            returnMessage = clientDoneMessages.get(clientID);
            returnMessage.append(message);
        } else {
            returnMessage = new StringBuilder(message.body());
        }
        clientDoneMessages.put(clientID, returnMessage);
    }

    private void handleClientCompletion(String clientID) {
        String resultFilePath = completedTaskDir + clientID + ".DONE";
        uploadFileToS3(resultFilePath, clientDoneMessages.get(clientID).toString());

        System.out.println("All tasks for client " + clientID + " are complete. Sending message to local app.");
        String manager2clientIdQueue = getOrCreateQueueUrl("manager2_clientID_" + clientID);


        sendMessageToQueue(manager2clientIdQueue, resultFilePath, clientID);

        clientDoneMessages.remove(clientID);
        clientMessages.remove(clientID);
    }

    private void handleClientMessage(Message clientMessage) {
        System.out.println("Handling client message: " + clientMessage.body());
        if (clientMessage.body().equals("TERMINATE") && !getIsTerminating()) {
            if (isTerminating.compareAndSet(false, true)) {
                System.out.print("Received terminate message... ");
                terminate();
            } else {
                System.out.print("Terminate message has already been received... ");
            }
            return;
        }

        if (!clientMessage.body().startsWith(newTasksDir) || !clientMessage.body().endsWith(".TASK")) {
            throw new IllegalArgumentException("Invalid New Task message: " + clientMessage.body());
        }

        String requestPath = clientMessage.body();
        String newTasks = readFileFromS3(requestPath);
        String clientID = getClientIdFromMessage(clientMessage);
        System.out.println("Client ID: " + clientID + "\n");
        clientMessages.put(clientID, 0);
        sendTasksToWorkers(newTasks, clientID);
        System.out.println("Tasks sent to workers. Deleting message from queue...");
        deleteMessageFromQueue(client2managerUrl, clientMessage);
        syncWorkerCount();
    }

    private void sendTasksToWorkers(String newTasks, String clientID) {
        StringBuilder workerMessage = new StringBuilder();
        int newMessageTasks = 0;

        for (String task : newTasks.split("\n")) {
            workerMessage.append(task).append("\n");
            newMessageTasks++;
            if (newMessageTasks == tasksPerWorker) {
                System.out.println("sending " + tasksPerWorker + " tasks to workers...");
                sendMessageToQueue(manager2workersUrl, workerMessage.toString(), clientID);
                clientMessages.put(clientID, clientMessages.get(clientID) + 1);
                incrementGlobalPacketsCounter();
                newMessageTasks = 0;
                workerMessage = new StringBuilder();
            }
        }

        if (newMessageTasks > 0) {
            System.out.println("Sending remaining " + newMessageTasks + " tasks to workers...");
            sendMessageToQueue(manager2workersUrl, workerMessage.toString(), clientID);
            clientMessages.put(clientID, clientMessages.get(clientID) + 1);
            incrementGlobalPacketsCounter();
        }
    }

    private void syncWorkerCount() {

        synchronized (lock) {
            int requiredWorkers = getGlobalPacketsCounter();
            System.out.println("\n" + "Required workers: " + requiredWorkers + ", Instances count: " +
                    (workerCount + 1) + ", Max allowed: 9");

            if (requiredWorkers > workerCount) {
                int workersToOpen = (Math.max((requiredWorkers - workerCount), 0));
                workersToOpen = Math.min(workersToOpen, MAX_INSTANCES - (workerCount + 1));
                System.out.println("Workers to open: " + workersToOpen);

                for (int i = 0; i < workersToOpen; i++) {
//                    System.out.println("Starting worker...");
                    String workerId = startWorker(WorkerScript);
                    if (workerId != null) {
                        int workerNumber = (workerCount + 1);
                        System.out.println("Started worker number: " + workerNumber + ", with id: " + workerId);
                        workersList.add(workerId);
                        workerCount++;
                    }
                }

            } else if (requiredWorkers < workerCount) {
                int workersToClose = Math.max(workerCount - requiredWorkers, 0);
                for (int i = 0; i < workersToClose; i++) {
                    String workerId = removeWorker(workersList.get(workersList.size() - 1));
                    if (workerId != null) {
                        System.out.println("Worker: " + workerId + " removed");
                        workersList.remove(workerId);
                        workerCount--;
                    } else {
                        System.out.println("Worker failed to be removed");
                        i++;
                    }
                }
            }
        }
    }

    private void terminate() {
        System.out.println("Finalizing existed tasks..." + "\n");
        try {
            // Wait for workers to finish
            while (getGlobalPacketsCounter() > 0) {
                System.out.println("Waiting for workers to finish...");
                Thread.sleep(1000); // Check every one second
            }
            //wait for the local apps to finish
            while (!clientMessages.isEmpty()) {
                System.out.println("waiting for local-apps to finish...");
                Thread.sleep(1000);
            }
            cleanUpResources();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
    }

    private void cleanUpResources() {
//        System.out.println("Cleaning up resources...");
        // Terminate workers and prepare for shutdown
        synchronized (lock) {
            try {
                clearResources();
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Failed to clean up resources.");
            }
        }
    }

    // Increment globalTasksCounter
    private void incrementGlobalPacketsCounter() {
        globalPacketsCounter.updateAndGet(value -> value + 1);
    }

    // Decrement globalTasksCounter
    private void decrementGlobalPacketsCounter() {
        globalPacketsCounter.updateAndGet(value -> value - 1);
    }

    private int getGlobalPacketsCounter() {
        return globalPacketsCounter.get();
    }

    private boolean getIsTerminating() {
        return isTerminating.get();
    }

    public static void main(String[] args) {
        System.out.println("Manager main started...");
        System.out.println("args: " + args[0]);
        Manager manager = new Manager(Integer.parseInt(args[0]));
        manager.run();
    }
}