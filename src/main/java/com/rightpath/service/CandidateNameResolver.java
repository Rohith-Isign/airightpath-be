package com.rightpath.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rightpath.entity.Users;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.UsersRepository;

/**
 * The one name the interview calls the candidate by: the first name on their account, the same one the welcome uses.
 *
 * <p>The interviewer greeted the candidate by their account name and later switched to a name it had picked up
 * elsewhere: one speech-to-text misheard ("Sahil" for "Sai") or one written on the resume. Every part of the interview
 * now takes the name from here, so it stays the same from the first sentence to the last.</p>
 */
@Component
public class CandidateNameResolver {

    private static final Logger log = LoggerFactory.getLogger(CandidateNameResolver.class);

    private final UsersRepository usersRepository;
    private final CandidateInterviewScheduleRepository scheduleRepo;

    /** By interview: the name does not change during one, and audio chunks ask for it many times a minute. */
    private final Map<Long, String> byInterview = new ConcurrentHashMap<>();

    public CandidateNameResolver(UsersRepository usersRepository, CandidateInterviewScheduleRepository scheduleRepo) {
        this.usersRepository = usersRepository;
        this.scheduleRepo = scheduleRepo;
    }

    /** The first name on the candidate's account, or empty when none is on file. */
    public Optional<String> firstName(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            return usersRepository.findByEmail(email)
                    .map(Users::getFirstName)
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim);
        } catch (Exception e) {
            log.warn("Could not read the candidate's name for {}", email, e);
            return Optional.empty();
        }
    }

    /** The first name of the candidate sitting this interview, or empty when none is on file. */
    public Optional<String> forInterview(Long scheduleId) {
        if (scheduleId == null) {
            return Optional.empty();
        }
        String cached = byInterview.get(scheduleId);
        if (cached != null) {
            return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
        }
        Optional<String> name = scheduleRepo.findById(scheduleId)
                .flatMap(schedule -> firstName(schedule.getEmail()));
        byInterview.put(scheduleId, name.orElse(""));
        return name;
    }

    /** The instruction given to the interviewer every turn, so the name never drifts. */
    public String namingRule(Long scheduleId) {
        return forInterview(scheduleId)
                .map(name -> "The candidate's name is " + name + ". If you address them by name, use exactly \""
                        + name + "\" and nothing else. Never call them by any other name: not a name in the "
                        + "resume, and not a name you hear in their answers, because speech-to-text often "
                        + "mishears names. If you are unsure, do not use a name at all.")
                .orElse("Do not address the candidate by name. Their name is not on file, and a name in the "
                        + "resume or heard in their answers may be wrong.");
    }
}
