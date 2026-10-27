package com.rightpath.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;

@Service
public class WhatsAppService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WhatsAppService.class);

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.from.number}")
    private String fromNumber;

    public enum MessageType {
        REGISTRATION_SUCCESS,
        JOB_APPLIED,
        EXAM_SCHEDULE,
        OTP,
        PASSWORD_UPDATE,
        EXAM_SUBMISSION,
        CODING_EXAM_SUBMISSION,
        SHORTLIST, REJECTION,
        RECONFIRMATION,
        ACKNOWLEDGED,
        TestFailed
    }

    /**
     * Universal method to send WhatsApp messages based on message type
     * 
     * @param toNumber Recipient's phone number
     * @param messageType Type of message to send
     * @param params Variable arguments depending on message type:
     *               - REGISTRATION_SUCCESS: firstName, lastName
     *               - JOB_APPLIED: firstName, lastName, jobPrefix, jobTitle
     *               - EXAM_SCHEDULE: startTime, endTime
     *               - OTP: otp
     *               - PASSWORD_UPDATE: (no additional params)
     *               - EXAM_SUBMISSION: (no additional params)
     *               - CODING_EXAM_SUBMISSION: (no additional params)
     *               - SHORTLIST: fullName
     */
    public void sendWhatsAppMessage(String toNumber, MessageType messageType, Object... params) {
        // Twilio is optional. Without credentials every call failed with
        // "Authentication Error - No credentials provided" and a stack trace;
        // skip quietly instead, so OTP/notification flows are unaffected.
        if (accountSid == null || accountSid.isBlank() || authToken == null || authToken.isBlank()) {
            log.debug("WhatsApp disabled (no Twilio credentials); skipping {}", messageType);
            return;
        }
        Twilio.init(accountSid, authToken);

        String formattedNumber = formatIndianMobileNumber(toNumber);
        if (formattedNumber == null) {
            System.err.println("❌ Invalid mobile number: " + toNumber);
            return;
        }

        String messageBody = generateMessageBody(messageType, params);
        if (messageBody == null) {
            System.err.println("❌ Invalid parameters for message type: " + messageType);
            return;
        }

        System.out.println("📲 TRY WhatsApp to: whatsapp:" + formattedNumber);
        System.out.println("📝 BODY: " + messageBody);

        try {
            Message message = Message.creator(
                    new com.twilio.type.PhoneNumber("whatsapp:" + formattedNumber),
                    new com.twilio.type.PhoneNumber(fromNumber),
                    messageBody
            ).create();

            System.out.println("✅ SENT SID=" + message.getSid() + " Status=" + message.getStatus());
        } catch (Exception e) {
            System.err.println("❌ ERROR " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String generateMessageBody(MessageType messageType, Object... params) {
        switch (messageType) {
            case REGISTRATION_SUCCESS:
                if (params.length != 2) return null;
                return String.format(
                    "🎉 *Welcome to RightPath, %s %s!*\n\n" +
                    "Your account has been created successfully.\n\n" +
                    "You can now log in to:\n" +
                    "👉 Explore resources\n" +
                    "👉 Track applications\n" +
                    "👉 Grow your career\n\n" +
                    "Best regards,\nRightPath Team",
                    params[0], params[1]
                );

            case JOB_APPLIED:
                if (params.length != 4) return null;
                return String.format(
                    "🎉 *Hi %s %s!*\n\n" +
                    "You have successfully applied for the job %s (%s)\n\n" +
                    "We will get back to you if your profile is shortlisted.\n" +
                    "Best regards,\nRightPath Team",
                    params[0], params[1], params[3], params[2]
                );

            case EXAM_SCHEDULE:
                if (params.length != 2 || !(params[0] instanceof LocalDateTime) || !(params[1] instanceof LocalDateTime)) 
                    return null;
                
                LocalDateTime startTime = (LocalDateTime) params[0];
                LocalDateTime endTime = (LocalDateTime) params[1];
                DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");
                DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("hh:mm a");
                
                return String.format(
                    "📚 *Acknowledgement *\n\n" +
                    "You are shortlisted*\n\n"+
                    "Date: %s\n" +
                    "Time: %s to %s\n\n" +
                    "Please:\n" +
                    "➡️ Please confirm you availability by acknowledging in email \n" +
                    "Good luck!\nRightPath Exams Team",
                    startTime.toLocalDate().format(dateFmt),
                    startTime.toLocalTime().format(timeFmt),
                    endTime.toLocalTime().format(timeFmt)
                );

            case ACKNOWLEDGED:
                return "✅ *Acknowledgement Received *\n\n" +
                       "We have received your acknowledgement for the Associate System Engineer position.\n\n" +
                       "Our Team will contact you with Further Updates.\n\n" +
                       "Stay Tuned,\nRightPath Team";

                
            case RECONFIRMATION:
                if (params.length != 2 || !(params[0] instanceof LocalDateTime) || !(params[1] instanceof LocalDateTime)) 
                    return null;
                
                LocalDateTime startime = (LocalDateTime) params[0];
                LocalDateTime endime = (LocalDateTime) params[1];
                DateTimeFormatter datemt = DateTimeFormatter.ofPattern("dd MMM yyyy");
                DateTimeFormatter timemt = DateTimeFormatter.ofPattern("hh:mm a");
                
                return String.format(
                    "📚 *Your Exam Schedule*\n\n" +
                    "Date: %s\n" +
                    "Time: %s to %s\n\n" +
                    "Please:\n" +
                    "➡️ Log in 10 mins early\n" +
                    "➡️ Ensure stable internet\n" +
                    "➡️ Choose quiet environment\n\n" +
                    "Good luck!\nRightPath Exams Team",
                    startime.toLocalDate().format(datemt),
                    startime.toLocalTime().format(timemt),
                    endime.toLocalTime().format(timemt)
                );
                
            case OTP:
                if (params.length != 1) return null;
                return String.format(
                    "🔒 *Password Reset OTP*\n\n" +
                    "Your verification code:\n" +
                    "*%s*\n\n" +
                    "Valid for 5 minutes.\n" +
                    "If you didn't request this, please ignore.\n\n" +
                    "RightPath Security Team",
                    params[0]
                );

            case PASSWORD_UPDATE:
                return "✅ *Password Updated Successfully*\n\n" +
                       "Your RightPath account password has been changed.\n\n" +
                       "If you didn't make this change, please contact support immediately.\n\n" +
                       "Stay secure,\nRightPath Team";

            case EXAM_SUBMISSION:
                return "📝 *Aptitude Exam Submitted Successfully*\n\n" +
                       "Thank you for completing your exam!\n\n" +
                       "We'll review your answers and our team will contact you if you qualify for the next stage.\n\n" +
                       "RightPath Exams Team";

            case CODING_EXAM_SUBMISSION:
                return "📝 *Coding Exam Submitted Successfully*\n\n" +
                       "Thank you for completing your exam!\n\n" +
                       "We'll review your answers and our team will contact you if you qualify for the next stage.\n\n" +
                       "RightPath Exams Team";

            case SHORTLIST:
                if (params.length != 1) return null;
                return String.format(
                    "🎯 *Congratulations %s!*\n\n" +
                    "Your resume has been *shortlisted* at RightPath!\n\n" +
                    "Our hiring team will contact you soon with next steps.\n\n" +
                    "Warm regards,\nRightPath Recruitment Team",
                    params[0]
                );
                
            case REJECTION:
                if (params.length != 1) return null;
                return String.format(
                    "Thank you for applying for the job position at RightPath.!*\n\n" +
                    "After careful consideration, we regret to inform you that your application was not successful at this time.!\n\n" +
                    "We appreciate your interest in our company and encourage you to apply for future opportunities.\n\n" +
                    "Warm regards,\nRightPath Recruitment Team",
                    params[0]
                );

            case TestFailed:
                if (params.length != 1) return null;
                return String.format(
                    "Thank you for taking the written test.!*\n\n" +
                    "While you didn't qualify this time, we're offering you.!\n\n" +
                    "Additional practice materials.\n\n" +
                    "Opportunity to retake the test after 30 days.\n\n" +
                    "Warm regards,\nRightPath Recruitment Team",
                    params[0]
                );
                
              
            default:
                return null;
        }
    }

    
    
//    <h2 style='color: #7f8c8d;'>Application Update</h2>
//    <p>Dear <strong>%s %s</strong>,</p>
//    <p>Thank you for taking the written test for <strong>%s</strong>.</p>
//    <p>While you didn't qualify this time, we're offering you:</p>
//    <ul>
//      <li>Additional practice materials</li>
//      <li>Opportunity to retake the test after 30 days</li>
//    </ul>
    
    
    /**
     * Formats a mobile number into E.164 format for Indian numbers.
     */
    private String formatIndianMobileNumber(String rawNumber) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) {
            return null;
        }

        String digitsOnly = rawNumber.replaceAll("[^0-9]", "");

        if (digitsOnly.length() == 10) {
            return "+91" + digitsOnly;
        } else if (digitsOnly.length() == 12 && digitsOnly.startsWith("91")) {
            return "+" + digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 11) {
            return "+91" + digitsOnly.substring(1);
        } else if (rawNumber.startsWith("+") && digitsOnly.length() >= 10) {
            return "+" + digitsOnly;
        }

        return null;
    }
}