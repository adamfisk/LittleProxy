package org.littleshoot.proxy.ntlm;

import static com.google.common.base.Preconditions.checkNotNull;
import static jcifs.ntlmssp.Type3Message.getDefaultFlags;
import static org.apache.commons.lang3.StringUtils.EMPTY;

import java.io.IOException;

import jcifs.ntlmssp.NtlmMessage;
import jcifs.ntlmssp.Type1Message;
import jcifs.ntlmssp.Type2Message;
import jcifs.ntlmssp.Type3Message;

/**
 * Reference implementation of {@link NtlmProvider}
 */
public class JcifsNtlmProvider implements NtlmProvider {

	private final int flags;

	private final String user;

	private final String password;

	private final String domain;

	private final String workstation;

	private Type2Message type2;

	public JcifsNtlmProvider(int flags, String user, String password, String domain, String workstation) {
		this.flags = flags > 0 ? flags: getDefaultFlags();
		this.user = checkNotNull(user);
		this.password = checkNotNull(password);
		this.domain = checkNotNull(domain);
		this.workstation = checkNotNull(workstation);
	}

	public JcifsNtlmProvider(String username, String password) {
		this(getDefaultFlags(), username, password, EMPTY, EMPTY);
	}

	@Override
	public byte[] getType1() {
		NtlmMessage type1 = new Type1Message(flags, padRight(domain, 32), padRight(workstation, 8));
		return type1.toByteArray();
	}

	@Override
	public void setType2(byte[] material) throws IOException {
		type2 = new Type2Message(material);
	}

	@Override
	public byte[] getType3() {
		NtlmMessage type3 = new Type3Message(type2, password, domain, user, workstation, type2.getFlags());
		return type3.toByteArray();
	}

	private static String padRight(String s, int n) {
		return String.format("%0$-" + n + "s", s);
	}

}
