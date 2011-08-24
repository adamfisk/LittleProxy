package org.littleshoot.proxy;

import javax.management.MXBean;

/**
 * Interface for JMX data on a single connection.
 */
@MXBean(true)
public interface ConnectionData {
    
    int getClientConnections();
    
    int getTotalClientConnections();
    
    int getOutgoingConnections();
    
    int getRequestsSent();
    
    int getResponsesReceived();
    
    String getUnansweredRequests();
    
    String getAnsweredReqeusts();
    
}
