package org.littleshoot.proxy;

/**
 * Connection tracer that we send through the system to maintain statistics on
 * how many bytes were sent on the wire.
 */
public class ConnectionTracer {
    private int bytesOnWire;

    public ConnectionTracer(int bytesOnWire) {
        super();
        this.bytesOnWire = bytesOnWire;
    }

    public int getBytesOnWire() {
        return bytesOnWire;
    }

}
