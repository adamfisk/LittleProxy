package org.littleshoot.proxy.haproxy;

class ProxyProtocolHeader {

    private String sourceAddress;
    private String destinationAddress;
    private String sourcePort;
    private String destinationPort;

    ProxyProtocolHeader(String sourceAddress, String destinationAddress, String sourcePort, String destinationPort) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
    }

    String getSourceAddress() {
        return sourceAddress;
    }

    String getDestinationAddress() {
        return destinationAddress;
    }

    String getSourcePort() {
        return sourcePort;
    }

    String getDestinationPort() {
        return destinationPort;
    }

}
