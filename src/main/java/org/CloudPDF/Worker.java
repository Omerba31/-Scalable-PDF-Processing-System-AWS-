package org.CloudPDF;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import software.amazon.awssdk.services.sqs.model.Message;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.StandardCharsets;

public class Worker extends AWS {
    private static final int pageIndex = 1;
    private boolean shouldTerminate = false;

    public Worker() {
        connectAWS();
    }

    public Worker(boolean debug) {
        if (!debug) {
            connectAWS();
        }
    }

    public void run() {
        System.out.println("Worker is running...");
        while (!shouldTerminate) {
            Message managerMessage = receiveMessageFromQueue(manager2workersUrl);
            if (managerMessage != null) {
                System.out.println("Received message");
                handleManagerMessage(managerMessage);
            }
        }
    }

    private void handleManagerMessage(Message managerMessage) {
        try {
            StringBuilder returnMessage = new StringBuilder();
            String clientID = getClientIdFromMessage(managerMessage);
            String s3PathDir = customerFilesDir + clientID + "/";
            for (String line : managerMessage.body().split("\n")) {
                if (line.trim().isEmpty()) {
                    System.out.println("Skipping invalid task line.");
                    continue;
                }

                if (line.contains("MD5OfMessageAttributes") || line.contains("MessageAttributes")) {
                    System.out.println("Skipping metadata line: " + line);
                    continue; // Skip metadata lines entirely //here
                }

                String[] taskParts = line.split("\\s+");
                if (taskParts.length != 2) {
                    returnMessage.append("invalid task line format: ").append(line).append("\n");
                    continue;
                }
                String operation = taskParts[0].trim();
                String pdfUrl = taskParts[1].trim();
                try {
                    String pdfName = extractPdfName(pdfUrl);
                    ByteArrayOutputStream pdfData = downloadPdf(pdfUrl);

                    String s3UploadPath = processPdfAndUpload(operation, pdfData, pdfName, s3PathDir);
                    returnMessage.append("Operation: ").append(operation)
                            .append(" <----> URL: ").append(pdfUrl)
                            .append(" <----> s3Path: ").append(s3UploadPath).append("\n'");

                } catch (Exception e) {
                    returnMessage.append("Operation: ").append(operation)
                            .append(" <----> URL: ").append(pdfUrl)
                            .append(" <----> error: ").append(e.getMessage()).append("\n'");
                }
            }
//            String sanitizedMessage = returnMessage.toString() //here
//                    .replaceAll(".*MD5OfMessageAttributes.*", "")  // Remove specific metadata lines
//                    .replaceAll(".*MessageAttributes.*", "")      // Remove any other metadata-related lines
//                    .trim();
//            sendMessageToQueue(workers2managerUrl, sanitizedMessage, clientID);

            sendMessageToQueue(workers2managerUrl, returnMessage.toString(), clientID); //here
            deleteMessageFromQueue(manager2workersUrl, managerMessage);

        } catch (Exception e) {
            throw new RuntimeException("Failed to process the manager message.", e);
        }
    }

    private String extractPdfName(String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isEmpty())
            throw new IllegalArgumentException("Invalid URL: URL is null or empty.");
        String fileName = pdfUrl.substring(pdfUrl.lastIndexOf("/") + 1);
        if (fileName.isEmpty())
            throw new IllegalArgumentException("Invalid URL: Unable to extract file name from URL.");
        return fileName;
    }

    private ByteArrayOutputStream downloadPdf(String pdfUrl) throws IOException {
        if (pdfUrl == null || pdfUrl.isEmpty()) {
            throw new IllegalArgumentException("Invalid URL: URL is null or empty.");
        }

        URL url = new URL(pdfUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Failed to download pdf from URL: " + pdfUrl);
        }

        try (InputStream inputStream = connection.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream;

        } finally {
            connection.disconnect();
        }
    }

    private String processPdfAndUpload(String operation, ByteArrayOutputStream pdfData, String pdfName, String s3Path) {
        ByteArrayOutputStream outputStream;
        String s3UploadPath;
        switch (operation.toUpperCase()) {
            case "TOTEXT" -> {
                outputStream = pdfToTXT(pdfData);
                s3UploadPath = s3Path + pdfName.replace(".pdf", ".txt");
            }
            case "TOHTML" -> {
                outputStream = pdfToHTML(pdfData);
                s3UploadPath = s3Path + pdfName.replace(".pdf", ".html");
            }
            case "TOIMAGE" -> {
                outputStream = pdfToPNG(pdfData);
                s3UploadPath = s3Path + pdfName.replace(".pdf", ".png");
            }
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        }

        uploadFileToS3(s3UploadPath, outputStream);
        return s3UploadPath;
    }

    private ByteArrayOutputStream pdfToTXT(ByteArrayOutputStream pdfData) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PDDocument document = PDDocument.load(pdfData.toByteArray())) {
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setStartPage(pageIndex);
            textStripper.setEndPage(pageIndex);
            String pdfText = textStripper.getText(document);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                writer.write(pdfText);
            } catch (IOException e) {
                handleError(e, "Failed to extract text or write TXT file");
            }
        } catch (IOException e) {
            handleError(e, "Unable to open the PDF file");
        }
        return outputStream;
    }

    private ByteArrayOutputStream pdfToHTML(ByteArrayOutputStream pdfData) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PDDocument document = PDDocument.load(pdfData.toByteArray())) {
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setStartPage(pageIndex);
            textStripper.setEndPage(pageIndex);
            String pdfText = textStripper.getText(document);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                writer.write("<html><head><style>pre { white-space: pre-wrap; word-wrap: break-word; }</style></head><body><pre>");
                writer.write(pdfText);
                writer.write("</pre></body></html>");
            }
        } catch (IOException e) {
            handleError(e, "Failed to extract text or write HTML file");
        }
        return outputStream;
    }

    private ByteArrayOutputStream pdfToPNG(ByteArrayOutputStream pdfData) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PDDocument document = PDDocument.load(pdfData.toByteArray())) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex - 1, 300, ImageType.RGB);

            ImageIO.write(image, "PNG", outputStream);
        } catch (IOException e) {
            handleError(e, "Failed to render or save PNG file");
        }
        return outputStream;
    }


    private void handleError(Exception e, String reason) {
        System.err.println(reason);
        System.err.println("Stack Trace:");
        e.printStackTrace(System.err);
    }

    public static void main(String[] args) {
        Worker worker = new Worker();
        worker.run();
    }
}