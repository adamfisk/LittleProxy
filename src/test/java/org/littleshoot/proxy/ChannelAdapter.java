package org.littleshoot.proxy;

import java.net.SocketAddress;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelConfig;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.DefaultChannelConfig;
import org.jboss.netty.channel.DefaultChannelFuture;

public class ChannelAdapter implements Channel {

    public ChannelFuture bind(SocketAddress localAddress) {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture close() {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture connect(SocketAddress remoteAddress) {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture disconnect() {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture getCloseFuture() {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelConfig getConfig() {
        return new DefaultChannelConfig();
    }

    public ChannelFactory getFactory() {
        // TODO Auto-generated method stub
        return null;
    }

    public Integer getId() {
        // TODO Auto-generated method stub
        return null;
    }

    public int getInterestOps() {
        // TODO Auto-generated method stub
        return 0;
    }

    public SocketAddress getLocalAddress() {
        // TODO Auto-generated method stub
        return null;
    }

    public Channel getParent() {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelPipeline getPipeline() {
        // TODO Auto-generated method stub
        return null;
    }

    public SocketAddress getRemoteAddress() {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean isBound() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isConnected() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isOpen() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isReadable() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isWritable() {
        // TODO Auto-generated method stub
        return false;
    }

    public ChannelFuture setInterestOps(int interestOps) {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture setReadable(boolean readable) {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture unbind() {
        // TODO Auto-generated method stub
        return null;
    }

    public ChannelFuture write(Object message) {
        return new ChannelFutureAdapter();
    }

    public ChannelFuture write(Object message, SocketAddress remoteAddress) {
        // TODO Auto-generated method stub
        return null;
    }

    public int compareTo(Channel o) {
        // TODO Auto-generated method stub
        return 0;
    }

}
