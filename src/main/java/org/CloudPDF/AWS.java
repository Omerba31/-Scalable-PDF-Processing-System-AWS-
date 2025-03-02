package org.CloudPDF;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AWS {

    protected SqsClient sqsClient;
    protected S3Client s3Client;
    protected Ec2Client ec2Client;

    protected String client2managerUrl;
    protected String manager2workersUrl;
    protected String workers2managerUrl;
    protected String bucketName = "dsp-01-omer";
//    protected String bucketName = "kita-dsp-01";

    protected String newTasksDir = "newTasks/";
    protected String completedTaskDir = "completedTasks/";
    protected String customerFilesDir = "customerFiles/";
    protected String jarsDir = "jars/";
    protected int MAX_INSTANCES = 9;

    protected void getOrCreateDirs() {
        checkAndCreateDirInS3(newTasksDir);
        checkAndCreateDirInS3(completedTaskDir);
        checkAndCreateDirInS3(customerFilesDir);
        checkAndCreateDirInS3(jarsDir);
    }

    protected void getOrCreateQueueUrls() {
        client2managerUrl = getOrCreateQueueUrl("client2manager");
        manager2workersUrl = getOrCreateQueueUrl("manager2workers");
        workers2managerUrl = getOrCreateQueueUrl("workers2manager");
    }

    protected void connectAWS() {
        System.out.println("Connecting to AWS...");
        sqsClient = SqsClient.builder().region(Region.US_WEST_2).build();
        s3Client = S3Client.builder().region(Region.US_WEST_2).build();
        ec2Client = Ec2Client.builder().region(Region.US_EAST_1).build();

        getOrCreateBucket(bucketName);
        getOrCreateQueueUrls();
    }

    protected void disconnectAWS() {
        System.out.println("Closing AWS connections...");
        sqsClient.close();
        s3Client.close();
        ec2Client.close();
    }

    protected void clearResources() {
        System.out.println("Clearing all resources...");
        deleteQueues();
        deleteALllDirectories();
        terminateAllInstances();
    }

    protected void deleteQueues() {
        try {
            ListQueuesResponse listQueuesResponse = sqsClient.listQueues();
            for (String queueUrl : listQueuesResponse.queueUrls()) {
                deleteQueue(queueUrl);
            }
        } catch (SqsException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to delete SQS queues", e);
        }
    }

    protected void deleteBucketDirsExceptJars() {
        System.out.println("Deleting all directories in S3 except jars...");
        try {
            ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .build();
            ListObjectsV2Response listObjectsResponse = s3Client.listObjectsV2(listObjectsRequest);

            for (S3Object s3Object : listObjectsResponse.contents()) {
                String key = s3Object.key();
//                if (!key.startsWith(jarsDir)) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build());
//                }
            }
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to delete directories in S3", e);
        }
    }

    protected void terminateAllInstances() {
        System.out.println("Terminating all running instances...");
        try {
            // Describe all running instances
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(Filter.builder()
                            .name("instance-state-name")
                            .values(InstanceStateName.RUNNING.toString())
                            .build())
                    .build();

            DescribeInstancesResponse response = ec2Client.describeInstances(request);

            // Collect all instance IDs
            List<String> instanceIds = new ArrayList<>();
            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    instanceIds.add(instance.instanceId());
                }
            }

            if (!instanceIds.isEmpty()) {
                // Terminate all instances
                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                        .instanceIds(instanceIds)
                        .build();
                ec2Client.terminateInstances(terminateRequest);
                System.out.println("Terminated instances: " + instanceIds);
            } else {
                System.out.println("No running instances found.");
            }
        } catch (Ec2Exception e) {
            e.printStackTrace();
            System.err.println("Failed to terminate instances: " + e.getMessage());
        }
    }

    public void setupJarsDirectory() {
//        uploadJarFile(jarsDir, "fatLocalAPP.jar");
        uploadJarFile(jarsDir, "fatManager.jar");
        uploadJarFile(jarsDir, "fatWorker.jar");
        System.out.println("Jars directory uploaded");
    }

    protected void uploadJarFile(String dir, String fileName) {
        String filePath = dir + fileName;
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucketName)
                .key(filePath)
                .build(), RequestBody.fromFile(new File("target/" + fileName)));
    }

    protected void cleanQueues() {
        System.out.println("Cleaning SQS queues...");
        try {
            ListQueuesResponse listQueuesResponse = sqsClient.listQueues();
            for (String queueUrl : listQueuesResponse.queueUrls()) {
                String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
                if (!queueName.equals("client2manager") &&
                        !queueName.equals("manager2workers") &&
                        !queueName.equals("workers2manager")) {
                    deleteQueue(queueUrl);
                } else {
                    purgeQueue(queueUrl);
                }
            }
        } catch (SqsException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to clean SQS queues", e);
        }
    }

    protected void checkAndCreateDirInS3(String dirPath) {
        try {
            ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(dirPath)
                    .maxKeys(1)
                    .build();
            ListObjectsV2Response listObjectsResponse = s3Client.listObjectsV2(listObjectsRequest);

            if (listObjectsResponse.contents().isEmpty()) {
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(dirPath)
                        .build(), RequestBody.empty());
            }
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to check or create directory in S3: " + dirPath, e);
        }
    }

    protected void purgeQueue(String queueUrl) {
        try {
            PurgeQueueRequest purgeQueueRequest = PurgeQueueRequest.builder()
                    .queueUrl(queueUrl)
                    .build();
            sqsClient.purgeQueue(purgeQueueRequest);
            System.out.println("Queue purged successfully: " + queueUrl);
        } catch (SqsException e) {
            System.err.println("Failed to purge queue: " + queueUrl + " - " + e.getMessage());
        }
    }

    protected void getOrCreateBucket(String bucketName) {
        try {
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();
            s3Client.headBucket(headBucketRequest);
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
            System.out.println("Bucket created: " + bucketName);
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to create or check bucket: " + bucketName, e);
        }
    }

    protected String getOrCreateQueueUrl(String queueName) {
        try {
            GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build();
            String queueUrl = sqsClient.getQueueUrl(getQueueRequest).queueUrl();
            return queueUrl;
        } catch (QueueDoesNotExistException e) {
            CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                    .queueName(queueName)
                    .build();
            CreateQueueResponse createQueueResponse = sqsClient.createQueue(createQueueRequest);
            return createQueueResponse.queueUrl();
        }
    }

    protected void uploadFileToS3(String s3Path, File file) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
            System.out.println("File uploaded to S3 at: " + s3Path);
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    protected void uploadFileToS3(String s3Path, String data) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(data));
            System.out.println("File uploaded to S3 at: " + s3Path);
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    protected void uploadFileToS3(String s3Path, ByteArrayOutputStream stream) {
//        System.out.println("Uploading file to S3...");
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(stream.toByteArray()));
            System.out.println("File uploaded to S3 at: " + s3Path);
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    protected String readFileFromS3(String s3Path) {
        System.out.println("Reading file from S3. Path: " + s3Path);
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .build();

            InputStream inputStream = s3Client.getObject(getObjectRequest);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            System.err.println("Failed to read file from S3: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    protected void sendMessageToQueue(String queueUrl, String message, String clientId) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        System.out.println("\n" + "Sending packet to queue for client:" + clientId + "\n" + message + "\n");
        messageAttributes.put("clientId", MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(clientId)
                .build());

        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message)
                .messageAttributes(messageAttributes)
                .build();

        sqsClient.sendMessage(send_msg_request);
    }

    protected Message receiveMessageFromQueue(String queueUrl, int waitTimeSeconds) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .messageAttributeNames("All")
                .waitTimeSeconds(waitTimeSeconds)
                .build();

        List<Message> messages = sqsClient.receiveMessage(request).messages();

        if (messages.isEmpty())
            return null;

        return messages.get(0);
    }

    protected Message receiveMessageFromQueue(String queueUrl) {
        return receiveMessageFromQueue(queueUrl, 0);
    }

    protected void deleteMessageFromQueue(String queueUrl, Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        sqsClient.deleteMessage(deleteRequest);
    }

    protected void deleteQueue(String queueUrl) {
        try {
            System.out.println("Queue deleted successfully: " + queueUrl);
            DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder()
                    .queueUrl(queueUrl)
                    .build();
            sqsClient.deleteQueue(deleteQueueRequest);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to delete queue: " + queueUrl);
        }
    }

    protected String getClientIdFromMessage(Message message) {
        if (message.messageAttributes().containsKey("clientId")) {
            return message.messageAttributes().get("clientId").stringValue();
        }
        return null;
    }

    protected String startWorker(String WorkerScript) {
//        System.out.println("Starting a new worker instance...");
        try {
            // Define the AMI ID and instance type (replace with your AMI ID)
            String amiId = "ami-054217b0faf130d36"; // Worker AMI ID
            InstanceType instanceType = InstanceType.T2_NANO;

            // Define the tags to assign to the instance
            Tag workerTag = Tag.builder()
                    .key("Role")
                    .value("worker")
                    .build();

            Tag nameTag = Tag.builder()
                    .key("Name")
                    .value("Worker")
                    .build();

            TagSpecification tagSpecification = TagSpecification.builder()
                    .resourceType("instance")
                    .tags(workerTag, nameTag)
                    .build();


            // Build the RunInstancesRequest
            RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                    .imageId(amiId)
                    .instanceType(instanceType)
                    .maxCount(1)
                    .minCount(1)
                    .tagSpecifications(tagSpecification)
//                    .keyName("vockey") // ALREADY SET IN
                    .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build())
                    .userData(Base64.getEncoder().encodeToString((WorkerScript).getBytes()))
                    .build();

            RunInstancesResponse workerInstance = ec2Client.runInstances(runInstancesRequest);
            String workerId = workerInstance.instances().get(0).instanceId();
//            System.out.println("worker instance started with ID: " + workerId);
            return workerId;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to start worker instance.");
            return null;
        }
    }

    protected String removeWorker(String workerId) {
//        System.out.println("Terminating worker instance with ID: " + workerId);
        try {
            // Build the TerminateInstancesRequest
            TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                    .instanceIds(workerId)
                    .build();

            // Terminate the instance
            TerminateInstancesResponse terminateResponse = ec2Client.terminateInstances(terminateRequest);

            // Log the termination status
            terminateResponse.terminatingInstances().forEach(instance -> {
//                System.out.println("Instance ID: " + instance.instanceId() +
//                        " - Previous state: " + instance.previousState().name() +
//                        " - Current state: " + instance.currentState().name());
            });

//            System.out.println("Worker instance terminated successfully.");
            return workerId;
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to terminate worker instance with ID: " + workerId);
            return null;
        }
    }

    protected boolean isManagerActive() {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(
                            Filter.builder().name("tag:Role").values("Manager").build(),
                            Filter.builder().name("instance-state-name").values("running", "pending").build()
                    ).build();

            // Send the request and get the response
            DescribeInstancesResponse response = ec2Client.describeInstances(request);

            // Check if any instance matches the filters
            List<Reservation> reservations = response.reservations();
            for (Reservation reservation : reservations) {
                if (!reservation.instances().isEmpty()) {
                    return true;
                }
            }
            return false;

        } catch (
                Exception e) {
            e.printStackTrace();
            System.err.println("Error while checking Manager instance status.");
            return false;
        }
    }

    protected void startManager(String ManagerScript) {
        try {
//            System.out.println("Starting Manager instance...");

            // Define the AMI ID and instance type (replace with your AMI ID)
            String amiId = "ami-054217b0faf130d36"; // Manager AMI ID
            InstanceType instanceType = InstanceType.T2_MEDIUM;

            // Define the tags to assign to the instance
            Tag managerTag = Tag.builder()
                    .key("Role")
                    .value("Manager")
                    .build();

            Tag nameTag = Tag.builder()
                    .key("Name")
                    .value("Manager")
                    .build();

            // Tag specification for the instance
            TagSpecification tagSpecification = TagSpecification.builder()
                    .resourceType("instance")
                    .tags(managerTag, nameTag)
                    .build();

            // Build the RunInstancesRequest
            RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                    .imageId(amiId)
                    .instanceType(instanceType)
                    .maxCount(1)
                    .minCount(1)
//                    .keyName("vockey") // ALREADY SET IN
                    .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build())
                    .userData(Base64.getEncoder().encodeToString((ManagerScript).getBytes()))
                    .tagSpecifications(tagSpecification)
                    .build();

            // Launch the instance
            RunInstancesResponse response = ec2Client.runInstances(runInstancesRequest);

            // Log the instance ID(s) of the newly launched Manager
            System.out.println("Manager instance started with ID: " + response.instances().get(0).instanceId());

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to start Manager instance.");
        }
    }

    protected void terminateManagerInstance() {
        try {
            // Describe the Manager instance
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(Filter.builder()
                            .name("tag:Role")
                            .values("Manager")
                            .build())
                    .build();

            DescribeInstancesResponse response = ec2Client.describeInstances(request);

            // Collect the Manager instance ID
            List<String> instanceIds = response.reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream())
                    .map(Instance::instanceId)
                    .collect(Collectors.toList());

            if (!instanceIds.isEmpty()) {
                // Terminate the Manager instance
                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                        .instanceIds(instanceIds)
                        .build();
                ec2Client.terminateInstances(terminateRequest);
                System.out.println("Terminated Manager instance: " + instanceIds);
            } else {
                System.out.println("No Manager instance found.");
            }
        } catch (Ec2Exception e) {
            e.printStackTrace();
            System.err.println("Failed to terminate Manager instance: " + e.getMessage());
        }
    }

    protected void deleteALllDirectories() {
        System.out.println("Deleting all directories in S3...");
        try {
            ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .build();
            ListObjectsV2Response listObjectsResponse = s3Client.listObjectsV2(listObjectsRequest);

            for (S3Object s3Object : listObjectsResponse.contents()) {
                String key = s3Object.key();
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build());
            }
        } catch (S3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to delete directories in S3", e);
        }
    }


}
