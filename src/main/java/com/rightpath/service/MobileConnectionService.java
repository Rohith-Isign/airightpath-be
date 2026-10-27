package com.rightpath.service;

public interface MobileConnectionService {

	void registerDesktop(String token, String sessionId);

	void registerMobile(String token, String sessionId);

	String getDesktopSession(String token);

	String getMobileSession(String token);
	
	void unregister(String token);

	/**
	 * Forget a phone whose socket closed, and say which pairing it was on.
	 *
	 * @return the pairing token, or null if this session was not a registered phone
	 *         (or the token has since been taken over by a newer phone session)
	 */
	String releaseMobileSession(String sessionId);

}
