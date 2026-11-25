package com.rightpath.service;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rightpath.service.state.SharedStateStore;

/**
 * One browser at a time per exam: an L2/L3 interview, or an aptitude or coding assessment.
 *
 * <p>The same exam could be started in two browsers at once and run in both. Now the page that opens it takes a
 * lease, renews it every few seconds while it is open, and gives it back when it closes. Another browser or tab asking
 * for the same exam while the lease is held is refused. A page reloaded in the same tab keeps its session id, so it
 * gets its own lease back at once; a browser that crashed or was closed without saying so stops renewing, and the
 * lease lapses within {@link #LEASE}, after which the exam can be opened elsewhere.</p>
 *
 * <p>Held in the shared state store, so it holds across server instances when that is Redis.</p>
 */
@Service
public class ExamSessionLock {

    private static final Logger log = LoggerFactory.getLogger(ExamSessionLock.class);

    /** How long a lease lasts without being renewed. The page renews it every 15 seconds. */
    public static final Duration LEASE = Duration.ofSeconds(45);

    /** What a session id from a browser must look like: a UUID, so nothing else can be stored under the key. */
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-fA-F-]{16,64}");

    public static final String INTERVIEW = "interview";
    public static final String ASSESSMENT = "assessment";

    /** What the candidate is told when the exam is already open elsewhere. */
    public static final String OPEN_ELSEWHERE = "This exam is already open in another browser or tab. Continue it "
            + "there, or close it and try again here in a minute.";

    private final SharedStateStore sharedState;

    public ExamSessionLock(SharedStateStore sharedState) {
        this.sharedState = sharedState;
    }

    /** Whether a value is acceptable as a session id. */
    public static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    private static String key(String kind, Long id) {
        return "exam-session:" + kind + ":" + id;
    }

    /** The session holding the exam now, if any. */
    public Optional<String> holder(String kind, Long id) {
        return sharedState.get(key(kind, id));
    }

    /**
     * Takes the lease for this session, or renews it if it already holds it. False when another session holds it.
     */
    public boolean claim(String kind, Long id, String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return false;
        }
        String key = key(kind, id);
        if (sharedState.setIfAbsent(key, sessionId, LEASE)) {
            return true;
        }
        Optional<String> current = sharedState.get(key);
        if (current.isEmpty()) {
            // Lapsed between the two calls: take it now.
            return sharedState.setIfAbsent(key, sessionId, LEASE);
        }
        if (current.get().equals(sessionId)) {
            sharedState.set(key, sessionId, LEASE);
            return true;
        }
        log.warn("Exam {} {} refused to session {}: open in session {}", kind, id, sessionId, current.get());
        return false;
    }

    /** Gives the lease back, if this session holds it. */
    public void release(String kind, Long id, String sessionId) {
        if (sessionId == null) {
            return;
        }
        String key = key(kind, id);
        if (sharedState.get(key).filter(sessionId::equals).isPresent()) {
            sharedState.delete(key);
        }
    }
}
