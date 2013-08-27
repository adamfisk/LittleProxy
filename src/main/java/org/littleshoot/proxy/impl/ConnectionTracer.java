package org.littleshoot.proxy.impl;

/**
 * Connection tracer that we send through the system to maintain statistics on
 * how many bytes were sent on the wire. We use this because, once a message has
 * been decoded/encoded into an {@link HttpObject}, the original # of bytes from
 * the wire is no longer available.
 */
class ConnectionTracer {
    private int bytesOnWire;

    ConnectionTracer(int bytesOnWire) {
        super();
        this.bytesOnWire = bytesOnWire;
    }

    int getBytesOnWire() {
        return bytesOnWire;
    }

}
