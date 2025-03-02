package org.CloudPDF;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.UUID;


public class LocalApplication extends AWS {
    private static final String clientID = UUID.randomUUID().toString();

    public LocalApplication() {
        connectAWS();
    }

    public void run(String inputFileName, String outputFileName, int tasksPerWorker, boolean terminate) {
        System.out.println("\n" + "Local application is running. Client ID: " + clientID);
        String ManagerScript = generateManagerScript(tasksPerWorker);

        try {
//            boolean lockAcquired = acquireS3Lock("manager-lock");
//            if (lockAcquired && !isManagerActive()) {
            if (!isManagerActive()) {
                System.out.println("Manager is not running. Starting Manager instance..." + "\n");
                setupJarsDirectory();
                startManager(ManagerScript);
//                releaseS3Lock("manager-lock");
            } else {
                System.out.println("Manager is already running. Proceeding with the current instance..." + "\n");
            }

            String s3TaskRequestPath = newTasksDir + clientID + ".TASK";
            File fileToUpload = new File(inputFileName);
            uploadFileToS3(s3TaskRequestPath, fileToUpload);
//            System.out.println("Client ID: " + clientID);
            sendMessageToQueue(client2managerUrl, s3TaskRequestPath, clientID);

            String summaryFilePath = waitForCompletion();
            System.out.println("Summary file path: " + summaryFilePath);
            createHtmlFromSummary(readFileFromS3(summaryFilePath), outputFileName);
            if (terminate) {
                sendTerminateMessage();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to get/start Manager instance.");
        } finally {
            disconnectAWS();
        }
    }


    private String generateManagerScript(int tasksPerWorker) {
        String JAR_LOCAL_DIR = "/home/ec2-user/ManagerFiles";
        String JAR_LOCAL_PATH = "/home/ec2-user/ManagerFiles/fatManager.jar";
        String JAR_S3_PATH = "jars/fatManager.jar";
        String S3_BUCKET_NAME = bucketName;
        String REGION = "us-west-2";

        return "#!/bin/bash" + "\n" +
                "exec > >(tee /var/log/manager-script.log | logger -t manager-script -s 2>/dev/console) 2>&1" + "\n" +
                "mkdir -p " + JAR_LOCAL_DIR + "\n" +
                "aws s3 cp s3://" + S3_BUCKET_NAME + "/" + JAR_S3_PATH + " " + JAR_LOCAL_PATH + " --region " + REGION + "\n" +
                "java -jar " + JAR_LOCAL_PATH + " " + tasksPerWorker + "\n";
    }

    private String waitForCompletion() {
        System.out.println("Waiting for task completion...");
        String manager2clientIdQueue = getOrCreateQueueUrl("manager2_clientID_" + clientID);
        while (true) {
            Message managerMessage = receiveMessageFromQueue(manager2clientIdQueue, 5);
            if (managerMessage != null) {
                deleteMessageFromQueue(manager2clientIdQueue, managerMessage);
                deleteQueue(manager2clientIdQueue);
                return managerMessage.body();
            }
        }
    }

    private void sendTerminateMessage() {
        System.out.println("Sending termination message...");
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(client2managerUrl)
                .messageBody("TERMINATE")
                .build();
        sqsClient.sendMessage(send_msg_request);
    }

    private void createHtmlFromSummary(String summary, String outputFileName) {
        try {
            // Get the directory of the running JAR
            File outputDir = getDir();

            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                if (!created) {
                    System.err.println("Failed to create directories: " + outputDir.getAbsolutePath());
                    return;
                }
            }

            // Define the full path for the output file
            File outputFile = createHTMLfile(summary, outputFileName, outputDir);

            // Print the absolute path of the created file
            System.out.println("HTML file successfully created at: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Error generating HTML file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static File getDir() throws URISyntaxException {
        File jarFile = new File(LocalApplication.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        File jarParentDir = jarFile.getParentFile(); // Parent directory of the JAR
        File jarGrandParentDir = jarParentDir.getParentFile(); // Grandparent directory of the JAR

        // Ensure the `storage/outputs` directory exists relative to the JAR's location
        File storageDir = new File(jarGrandParentDir, "storage");
        return new File(storageDir, "outputs");
    }

    private static File createHTMLfile(String summary, String outputFileName, File outputDir) throws IOException {
        File outputFile = new File(outputDir, outputFileName);

        try (FileWriter htmlWriter = new FileWriter(outputFile)) {
            htmlWriter.write("<!DOCTYPE html>");
            htmlWriter.write("<html lang='en'><head>");
            htmlWriter.write("<meta charset='UTF-8'>");
            htmlWriter.write("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            htmlWriter.write("<title>Task Summary Results</title>");
            htmlWriter.write("<style>");
            htmlWriter.write("body { font-family: Arial, sans-serif; margin: 20px; background-color: #f4f4f9; color: #333; }");
            htmlWriter.write("h1 { color: #444; border-bottom: 2px solid #ccc; padding-bottom: 10px; margin-bottom: 20px; }");
            htmlWriter.write(".summary-container { padding: 20px; background: #fff; border-radius: 8px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); }");
            htmlWriter.write("ul { list-style: none; padding: 0; margin: 0; counter-reset: line-number; }");
            htmlWriter.write("li { background: #fafafa; margin: 5px 0; padding: 15px 10px; border: 1px solid #ddd; border-radius: 5px; position: relative; }");
            htmlWriter.write("li::before { content: counter(line-number) '. '; counter-increment: line-number; ");
            htmlWriter.write("position: absolute; left: -30px; top: 50%; transform: translateY(-50%); color: #777; font-weight: bold; }");
            htmlWriter.write("</style>");
            htmlWriter.write("</head><body>");
            htmlWriter.write("<h1>Task Summary Results</h1>");
            htmlWriter.write("<div class='summary-container'>");
            htmlWriter.write("<ul>");

            for (String line : summary.split("\n")) {
                if (line.contains("Body=")) {
                    int bodyStart = line.indexOf("Body=") + 5;
                    String bodyContent = line.substring(bodyStart).trim();
                    htmlWriter.write("<li>" + bodyContent + "</li>");
                } else if (
                        line.contains("MD5OfMessageAttributes=") || line.contains("MessageAttributes=") ||
                        line.contains("clientID=") || line.contains("MessageAttributeValue")) {
                    // Do not print the line
                } else {
                    htmlWriter.write("<li>" + line + "</li>");
                }
            }

            htmlWriter.write("</ul>");
            htmlWriter.write("</div>");
            htmlWriter.write("</body></html>");
        }
        return outputFile;
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java -jar yourjar.jar inputFilePath outputFileName n [terminate]");
            return;
        }

        String inputFilePath = args[0];
        String outputFileName = args[1] + " - clientID: " + clientID + ".html";
        int tasksPerWorker = Integer.parseInt(args[2]);
        boolean terminate = args.length > 3 && args[3].equalsIgnoreCase("terminate");

        System.out.println("Args " + inputFilePath + " " + outputFileName + " " + tasksPerWorker + " " + terminate);
        LocalApplication localApp = new LocalApplication();
        localApp.run(inputFilePath, outputFileName, tasksPerWorker, terminate);

    }
}