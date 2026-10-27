package com.rightpath.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.rightpath.service.MobileConnectionService;

@Service
public class MobileConnectionServiceImpl  implements MobileConnectionService {
    private final Map<String, String> tokenToDesktopSession = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToMobileSession = new ConcurrentHashMap<>();

    public String generateToken(Long scheduleId) {
        return UUID.randomUUID().toString();
    }

    @Override
    public void registerDesktop(String token, String sessionId) {
        tokenToDesktopSession.put(token, sessionId);
    }

    @Override
    public void registerMobile(String token, String sessionId) {
        tokenToMobileSession.put(token, sessionId);
    }

    @Override
    public String getDesktopSession(String token) {
        return tokenToDesktopSession.get(token);
    }

    @Override
    public String getMobileSession(String token) {
        return tokenToMobileSession.get(token);
    }

    public void unregister(String token) {
        tokenToDesktopSession.remove(token);
        tokenToMobileSession.remove(token);
    }

    @Override
    public String releaseMobileSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : tokenToMobileSession.entrySet()) {
            // remove(key, value): a phone that already reconnected under a new
            // session keeps its registration.
            if (sessionId.equals(entry.getValue()) && tokenToMobileSession.remove(entry.getKey(), sessionId)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
