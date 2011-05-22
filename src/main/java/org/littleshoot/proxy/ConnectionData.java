package org.littleshoot.proxy;

import javax.management.MXBean;

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
