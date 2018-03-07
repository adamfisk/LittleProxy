package org.littleshoot.proxy;

/**
 * An extension to the {@link MitmManager} interface to allow MITM to be
 * selectively applied based on the peer. Added as a new interface to not
 * break existing implementations.
 */
public interface SelectiveMitmManager extends MitmManager {
    /**
     * Checks if MITM should be applied for a given peer.
     *
     * @param peerHost The peer host
     * @param peerPort The peer port
     *
     * @return true to continue with MITM, false to act as if MITM was not enabled for this peer and tunnel raw content.
     */
    boolean shouldMITMPeer(String peerHost, int peerPort);
}
