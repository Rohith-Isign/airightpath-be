package com.rightpath.controller;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rightpath.service.WhatsAppService;
import com.rightpath.service.WhatsAppService.MessageType;

/**
 * REST controller for WhatsApp messaging operations.
 */
@RestController
@RequestMapping("/whatsapp")
public class WhatsAppController {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppController.class);
    
    private final WhatsAppService whatsAppService;

    @Autowired
    public WhatsAppController(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    /**
     * Sends a WhatsApp message to the specified recipient.
     * 
     * @param to The recipient's phone number
     * @param type The type of message to send
     * @param params Comma-separated parameters needed for the message type
     * @return ResponseEntity with operation status
     */
    @PostMapping("/send")
    public ResponseEntity<String> sendWhatsAppMessage(
        @RequestParam String to,
        @RequestParam MessageType type,
        @RequestParam(required = false) String params
    ) {
        logger.info("Sending {} message to {}", type, to);

        // Handle different message types
        switch(type) {
            case REGISTRATION_SUCCESS:
                String[] names = params.split(",");
                whatsAppService.sendWhatsAppMessage(to, type, names[0], names[1]);
                break;

            case JOB_APPLIED:
                String[] jobParams = params.split(",");
                whatsAppService.sendWhatsAppMessage(to, type,
                    jobParams[0], jobParams[1], jobParams[2], jobParams[3]);
                break;

            case EXAM_SCHEDULE:
                String[] times = params.split(",");
                LocalDateTime start = LocalDateTime.parse(times[0]);
                LocalDateTime end = LocalDateTime.parse(times[1]);
                whatsAppService.sendWhatsAppMessage(to, type, start, end);
                break;

            case OTP:
                whatsAppService.sendWhatsAppMessage(to, type, params);
                break;

            case SHORTLIST:
                whatsAppService.sendWhatsAppMessage(to, type, params);
                break;

            default:
                whatsAppService.sendWhatsAppMessage(to, type);
        }

        return ResponseEntity.ok().body(type + " message sent successfully");
    }
}