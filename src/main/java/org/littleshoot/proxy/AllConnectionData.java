package org.littleshoot.proxy;

import javax.management.MXBean;

@MXBean(true)
public interface AllConnectionData {

    int getNumRequestHandlers();
}
