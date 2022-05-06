package org.littleshoot.proxy.extras;

import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

public class ProxyProtocolMessage {

    private final HAProxyProtocolVersion protocolVersion;
    private final HAProxyCommand command;
    private final HAProxyProxiedProtocol proxiedProtocol;
    private final String sourceAddress;
    private final String destinationAddress;
    private final int sourcePort;
    private final int destinationPort;

    public ProxyProtocolMessage(HAProxyProtocolVersion protocolVersion, HAProxyCommand command, HAProxyProxiedProtocol proxiedProtocol, String sourceAddress, String destinationAddress
        , int sourcePort, int destinationPort) {
        this.protocolVersion = protocolVersion;
        this.command = command;
        this.proxiedProtocol = proxiedProtocol;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
    }

    public ProxyProtocolMessage(HAProxyMessage haProxyMessage) {
        this.protocolVersion = haProxyMessage.protocolVersion();
        this.command = haProxyMessage.command();
        this.proxiedProtocol = haProxyMessage.proxiedProtocol();
        this.sourceAddress = haProxyMessage.sourceAddress();
        this.destinationAddress = haProxyMessage.destinationAddress();
        this.sourcePort = haProxyMessage.sourcePort();
        this.destinationPort = haProxyMessage.destinationPort();
    }

    public HAProxyProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public HAProxyCommand getCommand() {
        return command;
    }

    public HAProxyProxiedProtocol getProxiedProtocol() {
        return proxiedProtocol;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }
}
