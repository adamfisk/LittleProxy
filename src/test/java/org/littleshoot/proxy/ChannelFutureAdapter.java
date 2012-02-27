package org.littleshoot.proxy;

import java.util.concurrent.TimeUnit;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;

public class ChannelFutureAdapter implements ChannelFuture {

    public void addListener(ChannelFutureListener listener) {
        // TODO Auto-generated method stub

    }

    public ChannelFuture await() throws InterruptedException {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean await(long timeoutMillis) throws InterruptedException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean await(long timeout, TimeUnit unit)
            throws InterruptedException {
        // TODO Auto-generated method stub
        return false;
    }

    public ChannelFuture awaitUninterruptibly() {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean awaitUninterruptibly(long timeoutMillis) {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean cancel() {
        // TODO Auto-generated method stub
        return false;
    }

    public Throwable getCause() {
        // TODO Auto-generated method stub
        return null;
    }

    public Channel getChannel() {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean isCancelled() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isDone() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isSuccess() {
        // TODO Auto-generated method stub
        return false;
    }

    public void removeListener(ChannelFutureListener listener) {
        // TODO Auto-generated method stub

    }

    public boolean setFailure(Throwable cause) {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean setProgress(long amount, long current, long total) {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean setSuccess() {
        // TODO Auto-generated method stub
        return false;
    }

    public ChannelFuture rethrowIfFailed() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

}
