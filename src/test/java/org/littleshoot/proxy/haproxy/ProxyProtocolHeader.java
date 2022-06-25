package org.littleshoot.proxy.haproxy;

class ProxyProtocolHeader {

    private final String sourceAddress;
    private final String destinationAddress;
    private final String sourcePort;
    private final String destinationPort;

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
