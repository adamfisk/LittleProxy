package org.littleshoot.proxy.ntlm;

import java.io.IOException;

/**
 * Implementation should generate Type1 and Type3 messages, most probably using
 * some library. Refer reference implementation {@link JcifsNtlmProvider}
 */
public interface NtlmProvider {

	byte[] getType1();

	void setType2(byte[] material) throws IOException;

	byte[] getType3();

}
