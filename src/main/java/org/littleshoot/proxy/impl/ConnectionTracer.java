package org.littleshoot.proxy.impl;

/**
 * Connection tracer that we send through the system to maintain statistics on
 * how many bytes were sent on the wire.
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
