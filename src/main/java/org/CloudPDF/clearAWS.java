package org.CloudPDF;

public class clearAWS extends AWS {
    public void clean() {
        connectAWS();
        clearResources();
        deleteALllDirectories();
        getOrCreateDirs();
//        setupJarsDirectory();
        terminateAllInstances();
    }



    public static void main(String[] args) {
        clearAWS AWS = new clearAWS();
        AWS.clean();
    }
}
