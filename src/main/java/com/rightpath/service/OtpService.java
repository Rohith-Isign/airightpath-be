package com.rightpath.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.rightpath.config.AuthProperties;
import com.rightpath.entity.Otp;
import com.rightpath.entity.Users;
import com.rightpath.repository.OtpRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.util.OtpGenerator;

@Service
public class OtpService {
    private final OtpRepository otpRepository;
    private final UsersRepository usersRepository;
    private final EmailService emailService;
    private final WhatsAppService whatsAppService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthProperties props;

    public OtpService(
        OtpRepository otpRepository,
        UsersRepository usersRepository,
        EmailService emailService,
        WhatsAppService whatsAppService,
        AuthProperties props
    ) {
        this.props = props;
        this.otpRepository = otpRepository;
        this.usersRepository = usersRepository;
        this.emailService = emailService;
        this.whatsAppService = whatsAppService;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Generates and sends an OTP to the given email or mobile number.
     * The OTP is valid for 5 minutes.
     *
     * @param email  Email address (can be null)
     * @param mobile Mobile number (can be null)
     * @throws Exception if sending OTP fails
     */
    public void generateAndSendOtp(String email, String mobile) throws Exception {
        // A code's issue time is its expiry minus its lifetime, so the last request needs no column of its own.
        Optional<Otp> previous = email != null ? otpRepository.findByEmail(email)
                : mobile != null ? otpRepository.findByMobile(mobile) : Optional.empty();
        if (previous.isPresent() && previous.get().getExpirationTime() != null
                && previous.get().getExpirationTime().minusMinutes(5)
                        .plusSeconds(props.getOtp().getResendCooldownSeconds()).isAfter(LocalDateTime.now())) {
            throw new com.rightpath.exceptions.OtpRateLimitException(
                    "A code was sent a moment ago. Please wait before requesting another.");
        }

        String otp = OtpGenerator.generateOtp();
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(5);

        Otp otpEntity = new Otp();
        otpEntity.setOtp(otp);
        otpEntity.setExpirationTime(expiry);

        if (email != null) {
            // Replaces any earlier code: one live OTP per contact, with a fresh attempt budget.
            otpRepository.deleteAllByEmail(email);
            otpEntity.setEmail(email);
            otpRepository.save(otpEntity);
            emailService.sendOtpEmail(email, otp);
            System.out.println("OTP sent via Email to: " + email);
        } else if (mobile != null) {
            otpRepository.deleteAllByMobile(mobile);
            otpEntity.setMobile(mobile);
            otpRepository.save(otpEntity);
            // Updated to use universal WhatsApp message method
            whatsAppService.sendWhatsAppMessage(mobile, WhatsAppService.MessageType.OTP, otp);
            System.out.println("OTP sent via WhatsApp to: " + mobile);
        } else {
            throw new IllegalArgumentException("Email or mobile must be provided for OTP.");
        }
    }

    /**
     * Validates the OTP against the stored record for email or mobile.
     *
     * @param email    Email (nullable)
     * @param mobile   Mobile (nullable)
     * @param inputOtp OTP entered by user
     * @return true if valid and not expired, false otherwise
     */
    public boolean validateOtp(String email, String mobile, String inputOtp) {
        try {
            Optional<Otp> otpOptional = Optional.empty();

            if (email != null) {
                otpOptional = otpRepository.findByEmail(email);
            } else if (mobile != null) {
                otpOptional = otpRepository.findByMobile(mobile);
            }

            if (otpOptional.isEmpty()) {
                System.out.println("OTP not found for given contact.");
                return false;
            }

            Otp otpEntity = otpOptional.get();

            if (otpEntity.getExpirationTime().isBefore(LocalDateTime.now())) {
                System.out.println("OTP has expired.");
                return false;
            }

            if (otpEntity.getAttempts() >= props.getOtp().getMaxAttempts()) {
                // Out of guesses: the code is dead, a new one has to be requested.
                otpRepository.deleteById(otpEntity.getId());
                System.out.println("OTP attempts exhausted; code discarded.");
                return false;
            }

            boolean isValid = inputOtp != null && java.security.MessageDigest.isEqual(
                    otpEntity.getOtp().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    inputOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (isValid) {
                otpRepository.markVerified(otpEntity.getId());
            } else {
                otpRepository.incrementAttempts(otpEntity.getId());
                if (otpEntity.getAttempts() + 1 >= props.getOtp().getMaxAttempts()) {
                    otpRepository.deleteById(otpEntity.getId());
                    System.out.println("OTP attempts exhausted; code discarded.");
                }
            }
            System.out.println("OTP validation result: " + isValid);
            return isValid;

        } catch (Exception e) {
            System.err.println("Error during OTP validation: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Removes expired OTPs from the database every 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void removeExpiredOtps() {
        try {
            System.out.println("Scheduled cleanup: removing expired OTPs...");
            int deletedCount = otpRepository.deleteAllByExpirationTimeBefore(LocalDateTime.now());
            System.out.println("Expired OTPs removed: " + deletedCount);
        } catch (Exception e) {
            System.err.println("Error during expired OTP cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Validates OTP and updates the user's password.
     * Sends confirmation after successful update.
     *
     * @param email       Email of user (nullable)
     * @param mobile      Mobile of user (nullable)
     * @param newPassword New password to be set
     * @return true if update is successful
     */
    public boolean validateOtpAndUpdatePassword(String email, String mobile, String newPassword) {
        try {
            // The code must have been entered correctly first. This used to change the
            // password for anyone who knew an email address, without checking an OTP at all.
            Optional<Otp> verifiedOtp = email != null ? otpRepository.findByEmail(email)
                    : mobile != null ? otpRepository.findByMobile(mobile) : Optional.empty();
            if (verifiedOtp.isEmpty() || !verifiedOtp.get().isVerified()
                    || verifiedOtp.get().getExpirationTime().isBefore(LocalDateTime.now())) {
                System.out.println("Password update refused: no verified, unexpired OTP.");
                return false;
            }

            Optional<Users> userOptional;

            if (email != null) {
                userOptional = usersRepository.findByEmail(email);
            } else {
                userOptional = usersRepository.findByMobileNumber(mobile);
            }

            if (userOptional.isEmpty()) {
                System.out.println("User not found for password update.");
                return false;
            }

            Users user = userOptional.get();
            user.setPassword(passwordEncoder.encode(newPassword));
            usersRepository.save(user);
            // Single use.
            otpRepository.deleteById(verifiedOtp.get().getId());

            if (email != null) {
                emailService.updatedPasswordConfirmation(email, "Password Updated", "Your password has been updated.");
                System.out.println("Password update confirmation sent via email.");
            } else {
                // Updated to use universal WhatsApp message method
                whatsAppService.sendWhatsAppMessage(mobile, WhatsAppService.MessageType.PASSWORD_UPDATE);
                System.out.println("Password update confirmation sent via WhatsApp.");
            }

            return true;

        } catch (Exception e) {
            System.err.println("Error during password update: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
