package com.rightpath.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.VoiceConversationEntry;

public interface VoiceConversationEntryRepository extends JpaRepository<VoiceConversationEntry, Long> {

    List<VoiceConversationEntry> findByInterviewScheduleIdOrderByTimestampAsc(Long interviewScheduleId);

    /**
     * What the interviewer asked this candidate in their other sittings of the job, newest first, so a re-sit can be
     * told not to ask the same things again. {@code includeNullRound} is for interviews stored before rounds existed,
     * which are technical ones.
     */
    @org.springframework.data.jpa.repository.Query(
            "select e.content from VoiceConversationEntry e "
                    + "where e.role = com.rightpath.enums.ConversationRole.INTERVIEWER "
                    + "and e.interviewSchedule.email = :email and e.interviewSchedule.jobPrefix = :jobPrefix "
                    + "and e.interviewSchedule.id <> :excludeId "
                    + "and (e.interviewSchedule.round = :round "
                    + "or (:includeNullRound = true and e.interviewSchedule.round is null)) "
                    + "order by e.timestamp desc")
    List<String> findEarlierQuestions(
            @org.springframework.data.repository.query.Param("email") String email,
            @org.springframework.data.repository.query.Param("jobPrefix") String jobPrefix,
            @org.springframework.data.repository.query.Param("excludeId") Long excludeId,
            @org.springframework.data.repository.query.Param("round") com.rightpath.enums.InterviewRound round,
            @org.springframework.data.repository.query.Param("includeNullRound") boolean includeNullRound,
            org.springframework.data.domain.Pageable page);
}
