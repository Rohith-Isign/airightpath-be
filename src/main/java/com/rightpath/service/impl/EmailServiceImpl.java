package com.rightpath.service.impl;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.Users;
import com.rightpath.enums.EmailType;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.EmailService;
import com.rightpath.service.WhatsAppService;
import com.rightpath.util.BusinessSchedule;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Every candidate-facing email leaves through here.
 *
 * <p>Each mail is one {@link Mail} description — subject, preheader, accent,
 * heading and body — poured into a single {@link #SHELL} layout, so the whole
 * hiring journey (applied → shortlisted → slot confirmation → reconfirmation →
 * test link → result) reads as one company writing to one person, rather than a
 * dozen differently-styled one-off HTML strings.</p>
 *
 * <p>Three rules the templates rely on:</p>
 * <ul>
 *   <li>every value is rendered through {@link #esc(Object)} or a formatter, and
 *       a row with no value is dropped, so a missing parameter shows nothing
 *       rather than the text {@code null};</li>
 *   <li>every scheduled slot goes through {@link BusinessSchedule}, so no raw ISO
 *       value can reach a candidate;</li>
 *   <li>every mail carries a plain-text alternative, a real From name and a
 *       working Reply-To — which is what keeps recruitment mail out of the spam
 *       folder and makes "just reply to this email" true.</li>
 * </ul>
 */
@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;
    private final WhatsAppService whatsAppService;
    private final UsersRepository userRepository;
    private final JobApplicationForCandidateRepository jobApplicationRepository;

    private static final String LOGO_CID = "rightpath-logo";
    private static final String LOGO_RESOURCE = "static/images/IsignLogo-removebg-preview.png";

    /**
     * Parameter key carrying a scheduled slot into a template. Its value must be a
     * {@link LocalDateTime} — templates render it through
     * {@link BusinessSchedule#formatForTemplate(Object)}, never directly, so a
     * pre-formatted or ISO string cannot reach candidate-facing copy.
     */
    public static final String EXAM_SCHEDULE_PARAM = "examSchedule";

    /** Brand blue: information the candidate simply needs to read. */
    private static final String ACCENT_INFO = "#1a56db";
    /** Green: something good happened — shortlisted, confirmed, cleared. */
    private static final String ACCENT_SUCCESS = "#0e7c4a";
    /** Amber: the candidate has to do something, and there is a deadline. */
    private static final String ACCENT_ACTION = "#b54708";
    /** Grey: regrets and security notices — never red at a person. */
    private static final String ACCENT_NEUTRAL = "#475467";

    /**
     * Every schedule shown to a candidate is rendered through this, so all
     * templates read identically ({@code Wed, 12 Aug 2026, 02:30 PM IST}) and no
     * raw ISO value can reach an email body.
     */
    private final BusinessSchedule businessSchedule;

    /**
     * The field initialisers below are the values used when this service is built
     * by hand (unit tests); Spring overwrites them from configuration at runtime.
     * Either way, no template ever renders a null brand value.
     */
    @Value("${spring.mail.username:}")
    private String fromEmail = "";

    /** What the candidate sees as the sender — a name, not a raw mailbox. */
    @Value("${app.mail.from-name:RightPath Careers}")
    private String fromName = "RightPath Careers";

    @Value("${app.mail.company-name:RightPath}")
    private String companyName = "RightPath";

    /** Where a candidate's reply lands. Falls back to the sending mailbox. */
    @Value("${app.mail.support-email:}")
    private String supportEmail = "";

    @Value("${app.mail.website:https://www.airightpath.com}")
    private String website = "https://www.airightpath.com";

    @Value("${app.mail.company-address:ISIGN TECH PRIVATE LIMITED, 1st floor, Plot No: 2-87/3, Mn Rd, beside Reliance Trends, Sreeramaaramam, Narsingi, Hyderabad, Telangana 500089}")
    private String companyAddress = "ISIGN TECH PRIVATE LIMITED, 1st floor, Plot No: 2-87/3, Mn Rd, beside Reliance Trends, Sreeramaaramam, Narsingi, Hyderabad, Telangana 500089";

    /** Where "Start my test" / "Go to my dashboard" send the candidate. */
    @Value("${app.mail.candidate-portal-url:https://www.airightpath.com}")
    private String portalUrl = "https://www.airightpath.com";

    /** On-site test address, shown on every slot mail so all three agree. */
    @Value("${app.mail.test-venue:ISIGN TECH PRIVATE LIMITED, 1st floor, Plot No: 2-87/3, Mn Rd, beside Reliance Trends, Sreeramaaramam, Narsingi, Hyderabad, Telangana 500089}")
    private String testVenue = "ISIGN TECH PRIVATE LIMITED, 1st floor, Plot No: 2-87/3, Mn Rd, beside Reliance Trends, Sreeramaaramam, Narsingi, Hyderabad, Telangana 500089";

    @Autowired
    public EmailServiceImpl(JavaMailSender mailSender, WhatsAppService whatsAppService,
                          UsersRepository userRepository, JobApplicationForCandidateRepository jobApplicationRepository,
                          BusinessSchedule businessSchedule) {
        this.mailSender = mailSender;
        this.whatsAppService = whatsAppService;
        this.userRepository = userRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.businessSchedule = businessSchedule;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /**
     * The one layout every mail is rendered into: logo, accent rule, eyebrow and
     * heading, body, then a footer saying who wrote and how to reach them.
     *
     * <p>Nested tables with inline styles, because that is the only markup Outlook
     * and Gmail both render predictably; a fluid outer table with
     * {@code max-width:600px} inside is what makes it readable on a phone.</p>
     */
    private static final String SHELL = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="x-apple-disable-message-reformatting">
              <title>{{subject}}</title>
            </head>
            <body style="margin:0;padding:0;background-color:#eef1f6;">
              <div style="display:none;font-size:1px;color:#eef1f6;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;">{{preheader}}</div>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#eef1f6;">
                <tr>
                  <td align="center" style="padding:24px 12px;">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;background-color:#ffffff;border:1px solid #e4e7ec;border-radius:12px;overflow:hidden;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                      <tr>
                        <td align="center" style="padding:26px 32px 20px;">
                          <img src="cid:{{logoCid}}" alt="{{companyName}}" width="150" style="width:150px;max-width:60%;height:auto;border:0;display:block;">
                        </td>
                      </tr>
                      <tr><td style="height:4px;line-height:4px;font-size:0;background-color:{{accent}};">&nbsp;</td></tr>
                      <tr>
                        <td style="padding:28px 32px 0;">
                          <p style="margin:0 0 8px;font-size:11px;font-weight:700;letter-spacing:.09em;text-transform:uppercase;color:{{accent}};">{{eyebrow}}</p>
                          <h1 style="margin:0 0 18px;font-size:22px;line-height:1.32;color:#101828;font-weight:700;">{{heading}}</h1>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:0 32px 30px;font-size:15px;line-height:1.65;color:#344054;">{{content}}</td>
                      </tr>
                      <tr>
                        <td style="padding:20px 32px 22px;background-color:#f8fafc;border-top:1px solid #e4e7ec;font-size:12px;line-height:1.65;color:#667085;">
                          <p style="margin:0 0 6px;"><strong style="color:#344054;">{{companyName}}</strong>{{addressSuffix}}</p>
                          {{contactLine}}
                          <p style="margin:0;color:#98a2b3;">You are receiving this because you have an application or an account with {{companyName}}. Please do not forward it — it carries details specific to you.</p>
                        </td>
                      </tr>
                    </table>
                    <p style="margin:14px auto 0;max-width:600px;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:11px;line-height:1.5;color:#98a2b3;text-align:center;">&copy; {{year}} {{companyName}}. All rights reserved.</p>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """;

    /** A body paragraph. */
    private static final String PARAGRAPH = """
            <p style="margin:0 0 14px;">{{text}}</p>
            """;

    /** Tinted panel holding the facts of the mail (slot, position, reference). */
    private static final String PANEL = """
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:4px 0 20px;background-color:#f8fafc;border:1px solid #e4e7ec;border-left:4px solid {{accent}};border-radius:8px;">
              <tr><td style="padding:16px 18px;font-size:15px;line-height:1.6;color:#344054;">{{rows}}</td></tr>
            </table>
            """;

    /**
     * One labelled fact, kept as a single inline line
     * ({@code <strong>Label:</strong> value}) so a slot reads identically in every
     * mail that shows one.
     */
    private static final String PANEL_ROW = """
            <p style="margin:0 0 8px;"><strong>{{label}}:</strong> {{value}}</p>
            """;

    /** Section title above a list of steps or things to carry. */
    private static final String SECTION_TITLE = """
            <p style="margin:22px 0 10px;font-size:15px;font-weight:700;color:#101828;">{{title}}</p>
            """;

    private static final String LIST_OPEN = """
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 16px;">
            """;

    private static final String LIST_ITEM = """
            <tr>
              <td width="26" valign="top" style="padding:0 0 8px;font-size:15px;line-height:1.6;color:{{accent}};font-weight:700;">{{marker}}</td>
              <td valign="top" style="padding:0 0 8px;font-size:15px;line-height:1.6;color:#344054;">{{text}}</td>
            </tr>
            """;

    private static final String LIST_CLOSE = "</table>\n";

    /** The one thing the mail wants the candidate to do. */
    private static final String BUTTON = """
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:6px auto 14px;">
              <tr>
                <td align="center" style="border-radius:6px;background-color:{{accent}};">
                  <a href="{{url}}" style="display:inline-block;padding:13px 30px;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:15px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:6px;">{{label}}</a>
                </td>
              </tr>
            </table>
            <p style="margin:0 0 18px;font-size:12px;line-height:1.6;color:#667085;text-align:center;word-break:break-all;">Button not working? Copy this link into your browser:<br><a href="{{url}}" style="color:{{accent}};">{{url}}</a></p>
            """;

    /** Verification code, sized to be read off a phone. */
    private static final String CODE_BOX = """
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:6px 0 18px;">
              <tr>
                <td align="center" style="padding:18px;background-color:#f8fafc;border:1px dashed {{accent}};border-radius:8px;">
                  <span style="font-family:'Courier New',Courier,monospace;font-size:32px;font-weight:700;letter-spacing:8px;color:#101828;">{{code}}</span>
                </td>
              </tr>
            </table>
            """;

    /** Quiet closing note — support, timelines, "no action needed". */
    private static final String NOTE = """
            <p style="margin:18px 0 0;padding-top:14px;border-top:1px solid #e4e7ec;font-size:13px;line-height:1.6;color:#667085;">{{text}}</p>
            """;

    /** A single composed mail, before it is poured into the {@link #SHELL}. */
    private record Mail(String subject, String preheader, String accent, String eyebrow, String heading,
            String content) {
    }

    // ------------------------------------------------------------------
    // Public API — the convenience methods all funnel into sendUniversalEmail
    // ------------------------------------------------------------------

    @Override
    public void sendSuccessRegistrationEmail(String toEmail, String lastName, String firstName, String mobileNumber) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", toEmail);
        params.put("firstName", firstName);
        params.put("lastName", lastName);
        params.put("mobileNumber", mobileNumber);
        sendUniversalEmail(EmailType.REGISTRATION_SUCCESS, params);
    }

    @Override
    public void sendExamLink(String email, LocalDateTime startTime, LocalDateTime endTime, String jobPrefix) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", email);
        params.put("startTime", startTime);
        params.put("endTime", endTime);
        params.put("jobPrefix", jobPrefix);
        // Name, role and mobile are enrichment, not preconditions: the candidate must
        // receive their test link even if the application row cannot be read back.
        enrichFromApplication(params, email, jobPrefix);
        sendUniversalEmail(EmailType.EXAM_SCHEDULE, params);
    }

    @Override
    public void sendOtpEmail(String to, String otp) {
        Optional<Users> user = userRepository.findByEmail(to);
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", to);
        params.put("otp", otp);
        user.ifPresent(u -> params.put("mobileNumber", u.getMobileNumber()));
        sendUniversalEmail(EmailType.OTP, params);
    }

    @Override
    public void updatedPasswordConfirmation(String to, String subject, String text) {
        Optional<Users> user = userRepository.findByEmail(to);
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", to);
        params.put("message", text);
        user.ifPresent(u -> params.put("mobileNumber", u.getMobileNumber()));
        sendUniversalEmail(EmailType.PASSWORD_UPDATED, params);
    }

    @Override
    public void sendSuccessExamAttend(String email, String jobPrefix) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", email);
        params.put("jobPrefix", jobPrefix);
        enrichFromApplication(params, email, jobPrefix);
        sendUniversalEmail(EmailType.EXAM_SUBMISSION, params);
    }

    @Override
    public void sendSuccessCodingExamAttend(String email, String jobPrefix) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", email);
        params.put("jobPrefix", jobPrefix);
        enrichFromApplication(params, email, jobPrefix);
        sendUniversalEmail(EmailType.CODING_EXAM_SUBMISSION, params);
    }

    @Override
    public void sendShortlistNotification(String toEmail, String fullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipientEmail", toEmail);
        params.put("fullName", fullName);
        sendUniversalEmail(EmailType.SHORTLIST_NOTIFICATION, params);
    }

    /**
     * Adds whatever the application row knows to a set of mail parameters, and
     * leaves them untouched when it knows nothing. Never blocks the send.
     */
    private void enrichFromApplication(Map<String, Object> params, String email, String jobPrefix) {
        try {
            List<JobApplicationForCandidate> applications =
                    jobApplicationRepository.findByJobPrefixAndEmail(jobPrefix, email);
            if (applications == null || applications.isEmpty()) {
                return;
            }
            JobApplicationForCandidate application = applications.get(0);
            params.putIfAbsent("firstName", application.getFirstName());
            params.putIfAbsent("lastName", application.getLastName());
            params.putIfAbsent("mobileNumber", application.getMobileNumber());
            if (application.getJobPost() != null) {
                params.putIfAbsent("jobTitle", application.getJobPost().getJobTitle());
            }
        } catch (Exception e) {
            log.warn("Could not enrich email for {} on job {}: {}", email, jobPrefix, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    @Override
    public void sendUniversalEmail(EmailType emailType, Map<String, Object> params) {
        String recipientEmail = str(params, "recipientEmail");
        Mail mail = compose(emailType, params);

        sendHtmlEmail(recipientEmail, mail.subject(), renderShell(mail));

        // Secondary channel, best effort: a WhatsApp failure must not look like a
        // failed email.
        sendWhatsAppNotification(emailType, params);
    }

    private Mail compose(EmailType emailType, Map<String, Object> params) {
        String firstName = str(params, "firstName");
        String jobTitle = str(params, "jobTitle");
        String jobRef = str(params, "jobPrefix");
        String role = jobTitle.isBlank() ? "the role you applied for" : "<strong>" + esc(jobTitle) + "</strong>";
        String forRole = jobTitle.isBlank() ? "" : " – " + jobTitle;

        return switch (emailType) {

            case REGISTRATION_SUCCESS -> new Mail(
                    "Welcome to " + companyName + " – your account is ready",
                    "Your account is active. Sign in to apply and track every stage of your application.",
                    ACCENT_INFO,
                    "Account created",
                    firstName.isBlank() ? "Welcome to " + esc(companyName) + "!"
                            : "Welcome aboard, " + esc(firstName) + "!",
                    greeting(params)
                            + paragraph("Your " + esc(companyName) + " account has been created successfully. "
                                    + "From your dashboard you can browse open roles, apply in a couple of clicks, "
                                    + "and follow every stage of your application in one place.")
                            + panel(ACCENT_INFO,
                                    row("Registered email", str(params, "recipientEmail")),
                                    row("Registered mobile", str(params, "mobileNumber")))
                            + button(ACCENT_INFO, portalUrl, "Go to my dashboard")
                            + note("Didn't create this account? Let us know at " + contactMailtoHtml()
                                    + " and we'll take care of it."));

            case APPLICATION_SUCCESS -> new Mail(
                    "Application received" + forRole + (jobRef.isBlank() ? "" : " (" + jobRef + ")"),
                    "We have your application. Here is your reference ID and what happens next.",
                    ACCENT_INFO,
                    "Application received",
                    firstName.isBlank() ? "Thank you for applying!" : "Thank you for applying, " + esc(firstName) + "!",
                    greeting(params)
                            + paragraph("We've received your application for " + role + " at " + esc(companyName)
                                    + ", and it is now with our recruitment team.")
                            + panel(ACCENT_INFO,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Current status", "Under review"))
                            + sectionTitle("What happens next")
                            + steps(ACCENT_INFO,
                                    "Our team reviews your profile against the role. This usually takes 3–5 business days.",
                                    "If your profile matches, we'll email you an invitation to the assessment with a date and time.",
                                    "Updates also reach you on your registered mobile number, so please keep it handy.")
                            + note("Please quote your reference ID in any correspondence. Questions? "
                                    + "Just reply to this email — it reaches our recruitment team."));

            case SHORTLIST_NOTIFICATION -> new Mail(
                    "Good news — you have been shortlisted" + forRole,
                    "Your profile has been shortlisted. We'll share your assessment details shortly.",
                    ACCENT_SUCCESS,
                    "Shortlisted",
                    "Congratulations, " + esc(candidateName(params)) + "!",
                    greeting(params)
                            + paragraph("Your profile has been <strong>shortlisted</strong> for " + role + " at "
                                    + esc(companyName) + ".")
                            + panel(ACCENT_SUCCESS,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Current status", "Shortlisted – assessment to be scheduled"))
                            + sectionTitle("What happens next")
                            + steps(ACCENT_SUCCESS,
                                    "Our HR team will email you a test date and time within the next 2 business days.",
                                    "You'll be asked to confirm that slot — please do so as soon as you can.",
                                    "Keep your phone reachable and a photo ID handy.")
                            + note("No action is needed from you right now. Questions? Reply to this email."));

            case ACKNOWLEDGEMENT -> new Mail(
                    "Action required: confirm your test slot" + forRole,
                    "Please confirm the test slot we have reserved for you.",
                    ACCENT_ACTION,
                    "Action required",
                    "Please confirm your test slot",
                    greeting(params)
                            + paragraph("You have been shortlisted for " + role
                                    + ", and we've reserved the slot below for your written test.")
                            + panel(ACCENT_ACTION,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    scheduleRow(params.get(EXAM_SCHEDULE_PARAM)),
                                    row("Venue", testVenue),
                                    row("Reporting time", "15 minutes before the start time"))
                            + paragraph("Kindly confirm your attendance so we can hold this slot for you:")
                            + button(ACCENT_ACTION, str(params, "acknowledgeUrl"), "Yes, I'll be there")
                            + note("If this time doesn't work for you, simply reply to this email and we'll do our "
                                    + "best to reschedule. Unconfirmed slots may be released to other candidates."));

            case ACKNOWLEDGEMENT_CONFIRMATION -> new Mail(
                    "Your test slot is confirmed" + forRole,
                    "Thanks for confirming. Here are your test details and what to carry.",
                    ACCENT_SUCCESS,
                    "Slot confirmed",
                    "Thank you for confirming!",
                    greeting(params)
                            + paragraph("We've received your confirmation for " + role
                                    + ". Your slot is now booked — please keep this email for your records.")
                            + panel(ACCENT_SUCCESS,
                                    row("Position", jobTitle),
                                    scheduleRow(params.get(EXAM_SCHEDULE_PARAM)),
                                    row("Venue", testVenue),
                                    row("Reporting time", "15 minutes before the start time"))
                            + sectionTitle("Please carry")
                            + bullets(ACCENT_SUCCESS,
                                    "A printed or digital copy of this email",
                                    "An original government-issued photo ID",
                                    "Your own pen and stationery")
                            + note("Running late or unable to attend? Tell us as early as you can by replying to "
                                    + "this email, and we'll try to find you another slot."));

            case RECONFIRMATION -> new Mail(
                    "Reminder: your test is coming up" + forRole,
                    "A reminder of your test date, venue and what to carry.",
                    ACCENT_ACTION,
                    "Reminder",
                    "Your test is scheduled",
                    greeting(params)
                            + paragraph("This is a friendly reminder about your upcoming written test for " + role + ".")
                            + panel(ACCENT_ACTION,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    scheduleRow(params.get(EXAM_SCHEDULE_PARAM)),
                                    row("Venue", testVenue),
                                    row("Reporting time", "15 minutes before the start time"))
                            + sectionTitle("Please carry")
                            + bullets(ACCENT_ACTION,
                                    "A printed or digital copy of this email",
                                    "An original government-issued photo ID",
                                    "Your own pen and stationery")
                            + note("Please allow for local traffic and reach a little early. If something comes up, "
                                    + "reply to this email straight away and we'll help."));

            case EXAM_SCHEDULE -> new Mail(
                    "Your online test is ready" + forRole,
                    "Your test window is open. You can start any time within the window shown inside.",
                    ACCENT_INFO,
                    "Assessment invitation",
                    "Your online test is ready",
                    greeting(params)
                            + paragraph("Your online assessment" + (jobTitle.isBlank() ? "" : " for " + role)
                                    + " is now available. You may begin any time inside the window below — "
                                    + "once you start, the test runs for its full duration.")
                            + panel(ACCENT_INFO,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Test window opens",
                                            businessSchedule.formatForTemplate(params.get("startTime"))),
                                    row("Test window closes",
                                            businessSchedule.formatForTemplate(params.get("endTime"))),
                                    row("Attempts allowed", "1"))
                            + button(ACCENT_INFO, portalUrl, "Start my test")
                            + sectionTitle("Before you begin")
                            + bullets(ACCENT_INFO,
                                    "Use a laptop or desktop with an up-to-date Chrome browser.",
                                    "Check that your internet connection is stable, and sit somewhere quiet.",
                                    "Sign in about 10 minutes early with your registered email address.",
                                    "Don't refresh, close the tab or switch windows during the test — it may submit automatically.")
                            + note("Trouble signing in or starting the test? Reply to this email before the window "
                                    + "closes and we'll sort it out with you."));

            case EXAM_SUBMISSION -> new Mail(
                    "We've received your aptitude test" + forRole,
                    "Your aptitude test was submitted successfully. Results follow in 5–7 business days.",
                    ACCENT_SUCCESS,
                    "Submission received",
                    "Your aptitude test has been submitted",
                    greeting(params)
                            + paragraph("Thank you for completing your aptitude test with " + esc(companyName)
                                    + ". Your responses have been recorded successfully.")
                            + panel(ACCENT_SUCCESS,
                                    row("Test", "Aptitude"),
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Current status", "Submitted – under evaluation"))
                            + paragraph("Our evaluation team will review your answers and get back to you within "
                                    + "<strong>5–7 business days</strong> if you qualify for the next stage.")
                            + note("No action is needed from you right now — please don't attempt the test again."));

            case CODING_EXAM_SUBMISSION -> new Mail(
                    "We've received your coding test" + forRole,
                    "Your coding test was submitted successfully. Results follow in 5–7 business days.",
                    ACCENT_SUCCESS,
                    "Submission received",
                    "Your coding test has been submitted",
                    greeting(params)
                            + paragraph("Thank you for completing your coding test with " + esc(companyName)
                                    + ". Your submission has been recorded successfully.")
                            + panel(ACCENT_SUCCESS,
                                    row("Test", "Coding"),
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Current status", "Submitted – under evaluation"))
                            + paragraph("Our evaluation team will review your code and get back to you within "
                                    + "<strong>5–7 business days</strong> if you qualify for the next stage.")
                            + note("No action is needed from you right now — please don't attempt the test again."));

            case INTERVIEW_SCHEDULE -> new Mail(
                    "Your interview is scheduled" + forRole,
                    "Your interview is ready. Please complete it before the deadline shown inside.",
                    ACCENT_INFO,
                    "Interview scheduled",
                    "Your interview is scheduled",
                    greeting(params)
                            + paragraph("Congratulations — you've been invited to the interview round"
                                    + (jobTitle.isBlank() ? "" : " for " + role) + ".")
                            + panel(ACCENT_INFO,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Available from",
                                            businessSchedule.formatForTemplate(params.get("startTime"))),
                                    row("Complete before",
                                            businessSchedule.formatForTemplate(params.get("endTime"))))
                            + button(ACCENT_INFO, portalUrl, "Start my interview")
                            + sectionTitle("Before you join")
                            + bullets(ACCENT_INFO,
                                    "Use a laptop or desktop with a working camera and microphone.",
                                    "Allow your browser access to the camera and microphone when prompted.",
                                    "Sit somewhere quiet and well lit, with a stable internet connection.",
                                    "Keep your photo ID nearby — you may be asked to show it.")
                            + note("Please finish before the deadline above. Facing a technical issue? "
                                    + "Reply to this email and we'll help."));

            case WRITTEN_TEST_SUCCESS -> new Mail(
                    "Congratulations — you've cleared the written test" + forRole,
                    "You've cleared the written test. Here's what happens next.",
                    ACCENT_SUCCESS,
                    "You're through",
                    "Congratulations, you've cleared the written test!",
                    greeting(params)
                            + paragraph("We're pleased to let you know that you have <strong>successfully cleared</strong> "
                                    + "the written test for " + role + " at " + esc(companyName) + ".")
                            + panel(ACCENT_SUCCESS,
                                    row("Position", jobTitle),
                                    row("Reference ID", jobRef),
                                    row("Current status", "Cleared – moving to the next round"))
                            + sectionTitle("What happens next")
                            + steps(ACCENT_SUCCESS,
                                    "Our HR team will contact you within 2–3 business days to schedule the next round.",
                                    "You'll receive the date, time and format by email and on your mobile number.",
                                    "Please keep an eye on your inbox, including the spam folder, just in case.")
                            + note("Congratulations once again — we're looking forward to speaking with you."));

            case WRITTEN_TEST_FAILURE -> new Mail(
                    "An update on your application" + forRole,
                    "An update on your application following the written test.",
                    ACCENT_NEUTRAL,
                    "Application update",
                    "An update on your application",
                    greeting(params)
                            + paragraph("Thank you for taking the time to attempt the written test for " + role
                                    + " at " + esc(companyName) + ".")
                            + paragraph("After reviewing your results, we're sorry to say that you have not been "
                                    + "selected for the next round on this occasion.")
                            + paragraph("This reflects the requirements of this particular role and the strength of "
                                    + "the applicant pool. Your profile stays in our talent pool, and you are very "
                                    + "welcome to apply again for roles that match your skills.")
                            + note("We appreciate the effort you put in, and we wish you every success. "
                                    + "Our current openings are listed at " + websiteLinkHtml() + "."));

            case REJECTION -> new Mail(
                    "An update on your application" + forRole,
                    "An update on the application you submitted to " + companyName + ".",
                    ACCENT_NEUTRAL,
                    "Application update",
                    "An update on your application",
                    greeting(params)
                            + paragraph("Thank you for your interest in " + role + " at " + esc(companyName)
                                    + ", and for the time you invested in your application.")
                            + paragraph("After careful consideration, we have decided not to take your application "
                                    + "forward at this stage.")
                            + paragraph("We had a strong set of applicants for this role, and this decision is about "
                                    + "fit for this particular position. We'll keep your profile on file and would be "
                                    + "glad to hear from you again for future openings.")
                            + note("Thank you once again for considering " + esc(companyName)
                                    + ". Our current openings are always listed at " + websiteLinkHtml() + "."));

            case OTP -> new Mail(
                    str(params, "otp") + " is your " + companyName + " verification code",
                    "Your verification code is valid for the next 5 minutes.",
                    ACCENT_INFO,
                    "Security",
                    "Here's your verification code",
                    paragraph("Use the code below to continue resetting your password:")
                            + codeBox(ACCENT_INFO, str(params, "otp"))
                            + paragraph("This code is valid for <strong>5 minutes</strong> and can be used once.")
                            + note("Never share this code with anyone — " + esc(companyName)
                                    + " will never ask you for it. If you didn't request a password reset, you can "
                                    + "safely ignore this email; your account stays unchanged."));

            case PASSWORD_UPDATED -> new Mail(
                    "Your " + companyName + " password was changed",
                    "Your password was changed successfully.",
                    ACCENT_NEUTRAL,
                    "Security",
                    "Your password was changed",
                    paragraph(str(params, "message").isBlank()
                            ? "Your " + esc(companyName) + " account password has been updated successfully."
                            : esc(str(params, "message")))
                            + panel(ACCENT_NEUTRAL, row("Account", str(params, "recipientEmail")))
                            + note("If this wasn't you, reset your password immediately and contact us at "
                                    + contactMailtoHtml() + "."));

            default -> throw new IllegalArgumentException("Unsupported email type: " + emailType);
        };
    }

    /** Pours a composed mail into the shared layout. */
    private String renderShell(Mail mail) {
        return fill(SHELL,
                "subject", esc(mail.subject()),
                "preheader", esc(mail.preheader()),
                "accent", mail.accent(),
                "eyebrow", esc(mail.eyebrow()),
                "heading", mail.heading(),
                "logoCid", LOGO_CID,
                "companyName", esc(companyName),
                "addressSuffix",
                companyAddress == null || companyAddress.isBlank() ? "" : " &middot; " + esc(companyAddress),
                "contactLine", footerContactLine(mail.accent()),
                "year", String.valueOf(Year.now(businessSchedule.zone()).getValue()),
                "content", mail.content());
    }

    // ------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------

    /** Opens the mail by name, and simply says "Dear Candidate" when we have none. */
    private String greeting(Map<String, Object> params) {
        return fill(PARAGRAPH, "text", "Dear <strong>" + esc(candidateName(params)) + "</strong>,");
    }

    private String paragraph(String html) {
        return fill(PARAGRAPH, "text", html);
    }

    private String sectionTitle(String title) {
        return fill(SECTION_TITLE, "title", esc(title));
    }

    private String note(String html) {
        return fill(NOTE, "text", html);
    }

    /** A fact panel; rows that had no value have already dropped out. */
    private String panel(String accent, String... rows) {
        StringBuilder body = new StringBuilder();
        for (String row : rows) {
            body.append(row);
        }
        if (body.length() == 0) {
            return "";
        }
        return fill(PANEL, "accent", accent, "rows", body.toString());
    }

    /** One {@code Label: value} line, or nothing at all when the value is missing. */
    private String row(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return fill(PANEL_ROW, "label", esc(label), "value", esc(value));
    }

    /**
     * The scheduled-slot line. Always rendered through {@link BusinessSchedule} and
     * always with the same label, so the acknowledgement, the confirmation and the
     * reminder cannot disagree about how one slot reads.
     */
    private String scheduleRow(Object slot) {
        return fill(PANEL_ROW, "label", esc("Date & Time"), "value", businessSchedule.formatForTemplate(slot));
    }

    private String steps(String accent, String... items) {
        return list(accent, true, items);
    }

    private String bullets(String accent, String... items) {
        return list(accent, false, items);
    }

    private String list(String accent, boolean numbered, String... items) {
        StringBuilder html = new StringBuilder(LIST_OPEN);
        int index = 1;
        for (String item : items) {
            html.append(fill(LIST_ITEM,
                    "accent", accent,
                    "marker", numbered ? index++ + "." : "&bull;",
                    "text", esc(item)));
        }
        return html.append(LIST_CLOSE).toString();
    }

    /** The call to action. Renders nothing when there is no link to offer. */
    private String button(String accent, String url, String label) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return fill(BUTTON, "accent", accent, "url", esc(url), "label", esc(label));
    }

    private String codeBox(String accent, String code) {
        return fill(CODE_BOX, "accent", accent, "code", esc(code));
    }

    private String footerContactLine(String accent) {
        String contact = contactEmail();
        boolean hasSite = website != null && !website.isBlank();
        if (contact.isBlank() && !hasSite) {
            return "";
        }
        StringBuilder line = new StringBuilder("<p style=\"margin:0 0 6px;\">");
        if (!contact.isBlank()) {
            line.append("Questions? Write to <a href=\"mailto:").append(esc(contact))
                    .append("\" style=\"color:").append(accent).append(";text-decoration:none;\">")
                    .append(esc(contact)).append("</a>");
        }
        if (hasSite) {
            line.append(contact.isBlank() ? "" : " &middot; ")
                    .append("<a href=\"").append(esc(website)).append("\" style=\"color:").append(accent)
                    .append(";text-decoration:none;\">").append(esc(website)).append("</a>");
        }
        return line.append("</p>\n").toString();
    }

    private String contactMailtoHtml() {
        String contact = contactEmail();
        return contact.isBlank() ? "our support team"
                : "<a href=\"mailto:" + esc(contact) + "\" style=\"color:" + ACCENT_INFO + ";\">" + esc(contact)
                        + "</a>";
    }

    private String websiteLinkHtml() {
        return website == null || website.isBlank() ? esc(companyName)
                : "<a href=\"" + esc(website) + "\" style=\"color:" + ACCENT_INFO + ";\">" + esc(website) + "</a>";
    }

    /** Replies go to the recruitment mailbox when one is configured, else to the sender. */
    private String contactEmail() {
        if (supportEmail != null && !supportEmail.isBlank()) {
            return supportEmail.trim();
        }
        return fromEmail == null ? "" : fromEmail.trim();
    }

    private static String candidateName(Map<String, Object> params) {
        String fullName = str(params, "fullName");
        if (!fullName.isBlank()) {
            return fullName;
        }
        String name = (str(params, "firstName") + " " + str(params, "lastName")).trim();
        return name.isBlank() ? "Candidate" : name;
    }

    private static String str(Map<String, Object> params, String key) {
        if (params == null) {
            return "";
        }
        Object value = params.get(key);
        return value == null ? "" : value.toString().trim();
    }

    /** Fills {@code {{key}}} placeholders; a null value renders as nothing. */
    private static String fill(String template, Object... keyValuePairs) {
        String out = template;
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            out = out.replace("{{" + keyValuePairs[i] + "}}", value == null ? "" : value.toString());
        }
        return out;
    }

    /** Keeps candidate-supplied text from breaking (or injecting into) the markup. */
    private static String esc(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name()
            );

            // A display name, so the candidate sees who is writing rather than a raw
            // mailbox — and a Reply-To, so "just reply to this email" is actually true.
            try {
                helper.setFrom(fromEmail, fromName);
            } catch (UnsupportedEncodingException e) {
                helper.setFrom(fromEmail);
            }
            String replyTo = contactEmail();
            if (!replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            // Plain-text alternative first, HTML second: clients that cannot render
            // HTML still get a readable mail, and filters score the pair better than
            // HTML alone.
            helper.setText(toPlainText(htmlBody), htmlBody);

            ClassPathResource logo = new ClassPathResource(LOGO_RESOURCE);
            if (logo.exists()) {
                helper.addInline(LOGO_CID, logo, "image/png");
            } else {
                log.warn("Email logo {} not found on the classpath; sending without it", LOGO_RESOURCE);
            }

            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email", e);
        }
    }

    /**
     * Renders an HTML body as readable text for the multipart alternative. Links
     * keep their URL beside the label, so nothing that was clickable becomes
     * unreachable.
     */
    static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html
                .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                // Line breaks in the text part come from the tags, never from how the
                // template source happens to be indented.
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("(?is)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "$2: $1")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                // A cell break is a space, not a line break: a bullet marker and its
                // text live in two cells and must stay on one line.
                .replaceAll("(?i)</t[dh]>", " ")
                .replaceAll("(?i)</(p|div|tr|h1|h2|h3|h4|li|ul|ol|table)>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&bull;", "-")
                .replace("&middot;", "-")
                .replace("&copy;", "(c)")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                // A link whose label was already the URL would otherwise read
                // "https://…: https://…".
                .replaceAll("(\\S+): \\1(?=\\s|$)", "$1")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    // ------------------------------------------------------------------
    // WhatsApp (secondary channel)
    // ------------------------------------------------------------------

    private void sendWhatsAppNotification(EmailType emailType, Map<String, Object> params) {
        try {
            String mobileNumber = str(params, "mobileNumber");
            if (mobileNumber.isEmpty()) {
                return;
            }

            switch (emailType) {
                case REGISTRATION_SUCCESS:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.REGISTRATION_SUCCESS,
                        params.get("firstName"),
                        params.get("lastName")
                    );
                    break;

                case EXAM_SCHEDULE:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.EXAM_SCHEDULE,
                        params.get("startTime"),
                        params.get("endTime")
                    );
                    break;

                case EXAM_SUBMISSION:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.EXAM_SUBMISSION
                    );
                    break;

                case CODING_EXAM_SUBMISSION:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.CODING_EXAM_SUBMISSION
                    );
                    break;

                case PASSWORD_UPDATED:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.PASSWORD_UPDATE
                    );
                    break;

                case SHORTLIST_NOTIFICATION:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.SHORTLIST,
                        candidateName(params)
                    );
                    break;

                case APPLICATION_SUCCESS:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.JOB_APPLIED,
                        params.get("firstName"),
                        params.get("lastName"),
                        params.get("jobPrefix"),
                        params.get("jobTitle")
                    );
                    break;

                case ACKNOWLEDGEMENT:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.EXAM_SCHEDULE,
                        params.get("startTime"),
                        params.get("endTime")
                    );
                    break;

                case REJECTION:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.REJECTION,
                        candidateName(params)
                    );
                    break;

                case WRITTEN_TEST_SUCCESS:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.SHORTLIST,
                        candidateName(params)
                    );
                    break;

                case WRITTEN_TEST_FAILURE:
                    whatsAppService.sendWhatsAppMessage(
                        mobileNumber,
                        WhatsAppService.MessageType.REJECTION
                    );
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("WhatsApp notification for {} failed: {}", emailType, e.getMessage());
        }
    }
}
