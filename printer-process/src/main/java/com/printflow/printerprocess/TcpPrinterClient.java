package com.printflow.printerprocess;

public class TcpPrinterClient {

    private final String serverHost;
    private final int serverPort;

    public TcpPrinterClient(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public void connect() {
        System.out.println("TCP Client connecting to " + serverHost + ":" + serverPort);
        // ToDo add real socket connection
    }
}
