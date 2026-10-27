package com.rightpath.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.rightpath.enums.EmailType;

/**
 * Fire-and-forget wrapper for mails a caller must not be made to wait on (bulk
 * sends, notifications alongside a saved record). A failure here is logged, not
 * thrown — the work that triggered the mail has already been committed.
 */
@Service
public class EmailAsyncService {

    private static final Logger log = LoggerFactory.getLogger(EmailAsyncService.class);

    private final EmailService emailService;

    public EmailAsyncService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Sends one of the branded templates in the background.
     *
     * @param emailType which template to render
     * @param params    template parameters, including {@code recipientEmail}
     */
    @Async
    public void sendTemplatedEmail(EmailType emailType, Map<String, Object> params) {
        Object recipient = params == null ? null : params.get("recipientEmail");
        try {
            emailService.sendUniversalEmail(emailType, params);
        } catch (Exception e) {
            log.warn("Async {} email failed for {}: {}", emailType, recipient, e.getMessage());
        }
    }

    @Async
    public void sendInterviewEmail(String to, String subject, String htmlBody) {
        try {
            emailService.sendHtmlEmail(to, subject, htmlBody);
        } catch (Exception e) {
            log.warn("Async email send failed to {}: {}", to, e.getMessage());
        }
    }
}
