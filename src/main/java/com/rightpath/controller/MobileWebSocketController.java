package com.rightpath.controller;

import com.rightpath.service.MobileConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Controller
@RequestMapping("/api")
public class MobileWebSocketController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MobileConnectionService mobileConnectionService;

    // Desktop registers with token
    @MessageMapping("/desktop/register")
    public void registerDesktop(@Payload Map<String, String> payload,
                                org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {
        String token = payload.get("token");
        String sessionId = headerAccessor.getSessionId();
        mobileConnectionService.registerDesktop(token, sessionId);

        // Notify mobile that desktop is ready (to trigger offer)
        String mobileSession = mobileConnectionService.getMobileSession(token);
        if (mobileSession != null) {
            messagingTemplate.convertAndSendToUser(mobileSession, "/queue/mobile/ready", Map.of("status", "ready"));
        }
    }

    /**
     * Mobile registers with token.
     *
     * <p>Both sides are told, and that is the fix for a pairing that only
     * worked by luck. The phone does not open its camera until it receives a
     * {@code ready}; it was only ever sent one when the <em>desktop</em>
     * registered. In the order this actually happens — the interview page is
     * open first, the candidate then picks up their phone and scans the code —
     * the desktop had already registered and gone quiet, so the phone sat
     * waiting for a signal that had been sent before it was listening. The
     * desktop meanwhile heard "mobile ready", said "Phone connected" and
     * showed an empty preview for the rest of the interview.</p>
     *
     * <p>Whichever side registers second now unblocks the other, so the
     * handshake no longer depends on who got there first.</p>
     */
    @MessageMapping("/mobile/register")
    public void registerMobile(@Payload Map<String, String> payload,
                               org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {
        String token = payload.get("token");
        String sessionId = headerAccessor.getSessionId();
        mobileConnectionService.registerMobile(token, sessionId);

        // Notify desktop that mobile is ready
        String desktopSession = mobileConnectionService.getDesktopSession(token);
        if (desktopSession != null) {
            messagingTemplate.convertAndSendToUser(desktopSession, "/queue/mobile/ready", Map.of("status", "ready"));
            // ...and the phone, which is the side that acts on it. A desktop
            // already waiting is the whole reason the candidate was given a
            // code to scan.
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/mobile/ready", Map.of("status", "ready"));
        }
    }

    /**
     * The desktop nudging the phone to start streaming.
     *
     * <p>The interview page has always sent this a second after it subscribes,
     * and it has always been dropped: there was no handler for the
     * destination, so Spring discarded the message and logged nothing the
     * client could see. It is the retry path — if the first handshake is
     * missed because one side reconnected, this is what starts the stream
     * without the candidate having to scan the code again.</p>
     */
    @MessageMapping("/mobile/ready/{token}")
    public void handleReady(@DestinationVariable String token,
                            @Payload(required = false) Map<String, Object> payload) {
        String mobileSession = mobileConnectionService.getMobileSession(token);
        if (mobileSession != null) {
            messagingTemplate.convertAndSendToUser(mobileSession, "/queue/mobile/ready",
                    payload == null ? Map.of("status", "ready") : payload);
        }
    }

    // Offer from desktop to mobile
    @MessageMapping("/mobile/offer/{token}")
    public void handleOffer(@DestinationVariable String token, @Payload Map<String, Object> offer) {
        String mobileSession = mobileConnectionService.getMobileSession(token);
        if (mobileSession != null) {
            messagingTemplate.convertAndSendToUser(mobileSession, "/queue/mobile/offer", offer);
        }
    }

    // Answer from mobile to desktop
    @MessageMapping("/mobile/answer/{token}")
    public void handleAnswer(@DestinationVariable String token, @Payload Map<String, Object> answer) {
        String desktopSession = mobileConnectionService.getDesktopSession(token);
        if (desktopSession != null) {
            messagingTemplate.convertAndSendToUser(desktopSession, "/queue/mobile/answer", answer);
        }
    }

    // Verification from mobile to desktop
    @MessageMapping("/mobile/verified/{token}")
    public void handleVerified(@DestinationVariable String token, @Payload Map<String, Object> payload) {
        String desktopSession = mobileConnectionService.getDesktopSession(token);
        if (desktopSession != null) {
            messagingTemplate.convertAndSendToUser(desktopSession, "/queue/mobile/verified", payload);
        }
    }

    /**
     * The desktop telling the phone the interview is over.
     *
     * <p>Without it the phone had no idea. It kept its camera on and its
     * torch-hot preview running after the interview had finished, and the
     * candidate was left holding a page that still looked live — so they
     * either sat there or closed it mid-upload. The phone releases the camera
     * on this and shows that it is done.</p>
     */
    @MessageMapping("/mobile/ended/{token}")
    public void handleInterviewEnded(@DestinationVariable String token,
                                     @Payload(required = false) Map<String, Object> payload) {
        String mobileSession = mobileConnectionService.getMobileSession(token);
        if (mobileSession != null) {
            messagingTemplate.convertAndSendToUser(mobileSession, "/queue/mobile/ended",
                    payload == null ? Map.of("status", "ended") : payload);
        }
    }

    // ICE candidate exchange
    @MessageMapping("/mobile/ice/{token}")
    public void handleIceCandidate(@DestinationVariable String token, @Payload Map<String, Object> candidate) {
        String target = (String) candidate.get("target");
        String sessionId = "mobile".equals(target)
                ? mobileConnectionService.getMobileSession(token)
                : mobileConnectionService.getDesktopSession(token);
        if (sessionId != null) {
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/mobile/ice", candidate);
        }
    }
}