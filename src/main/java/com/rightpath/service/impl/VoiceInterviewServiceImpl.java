package com.rightpath.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.dto.voice.PerformanceSnapshot;
import com.rightpath.dto.voice.ResumeResponse;
import com.rightpath.dto.voice.VoiceAnswerRequest;
import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.dto.voice.VoiceSessionStatus;
import com.rightpath.dto.voice.VoiceStartResponse;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.CompletionReason;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;
import com.rightpath.util.CodingAnswerFormatter;
import com.rightpath.util.BusinessSchedule;
import com.rightpath.util.InterviewReplyParser;
import com.rightpath.util.PromptInjectionGuard;
import com.rightpath.enums.InterviewResult;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.service.CandidatePerformanceAnalyzer;
import com.rightpath.service.InterviewConductPolicy;
import com.rightpath.service.InterviewContextService;
import com.rightpath.service.InterviewEvaluationService;
import com.rightpath.service.InterviewService;
import com.rightpath.service.InterviewTemplateService;
import com.rightpath.service.InterviewTopicCoverage;
import com.rightpath.service.OpenAiStreamingService;
import com.rightpath.service.TextToSpeechService;
import com.rightpath.service.ToneAnalysisService;
import com.rightpath.service.VoiceInterviewService;

@Service
public class VoiceInterviewServiceImpl implements VoiceInterviewService {
	
	@Autowired
	private InterviewService interviewService;

    private static final Logger log = LoggerFactory.getLogger(VoiceInterviewServiceImpl.class);

    private static final String INTERVIEW_COMPLETE_MARKER = "[INTERVIEW_COMPLETE]";
    private static final java.util.regex.Pattern QUESTION_TYPE_TAG_PATTERN =
            java.util.regex.Pattern.compile("\\[(CODING|THEORY|NON-TECH)]\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final List<String> FILLERS = List.of(
            "That's a great point, let me think about what I'd like to explore next...",
            "Interesting, I appreciate you sharing that. Let me consider my follow-up...",
            "Thank you for that detailed response. Give me a moment...",
            "That's really helpful context. Let me think about the best direction to go...",
            "I see, that's quite insightful. Let me formulate my next question...",
            "Great, I appreciate the thoroughness of your answer. One moment...",
            "That makes sense. Let me think about what would be most valuable to discuss next...",
            "Wonderful. Let me take a moment to consider the best follow-up..."
    );

    private static final Random random = new Random();

    private final CandidateInterviewScheduleRepository scheduleRepo;
    private final VoiceConversationEntryRepository entryRepository;
    private final OpenAiStreamingService openAiStreamingService;
    private final InterviewContextService contextService;
    private final TextToSpeechService textToSpeechService;
    private final ToneAnalysisService toneAnalysisService;
    private final InterviewEvaluationService evaluationService;
    private final CandidatePerformanceAnalyzer performanceAnalyzer;
    private final SimpMessagingTemplate messagingTemplate;
    private final TransactionTemplate transactionTemplate;
    private final InterviewConductPolicy conductPolicy;
    private final InterviewReplyParser replyParser;

    /** The zone interview slots are written in; see {@link BusinessSchedule}. */
    @Autowired
    private BusinessSchedule businessSchedule;
    private final InterviewTopicCoverage topicCoverage;
    private final PromptInjectionGuard injectionGuard;
    private final InterviewTemplateService templateService;
    private final com.rightpath.repository.UsersRepository usersRepository;

    @Value("${interview.max-warnings:5}")
    private int maxWarnings;

    /**
     * Whether reaching {@link #maxWarnings} ends the interview.
     *
     * <p>Off. Ending an interview on a count is a hiring decision made by a
     * threshold, and the signals feeding that count are not good enough to
     * carry it: a candidate who looked away from the camera five times was
     * marked {@code FAILED} with {@code PROCTORING_VIOLATION} and had no
     * further say. The warnings are still counted, still recorded against the
     * interview, and now flag it for a person to look at — which is the
     * decision a person should be making.</p>
     *
     * <p>The candidate's screen made this worse than it needed to be: it
     * showed the count against a ceiling of 999999 while the server was
     * terminating at five, so the one warning that mattered looked like
     * nothing.</p>
     */
    @Value("${interview.terminate-on-max-warnings:false}")
    private boolean terminateOnMaxWarnings;

    @Value("${interview.max-duration-minutes:60}")
    private int maxDurationMinutes;

    public VoiceInterviewServiceImpl(
            CandidateInterviewScheduleRepository scheduleRepo,
            VoiceConversationEntryRepository entryRepository,
            OpenAiStreamingService openAiStreamingService,
            InterviewContextService contextService,
            TextToSpeechService textToSpeechService,
            ToneAnalysisService toneAnalysisService,
            InterviewEvaluationService evaluationService,
            CandidatePerformanceAnalyzer performanceAnalyzer,
            SimpMessagingTemplate messagingTemplate,
            TransactionTemplate transactionTemplate,
            InterviewConductPolicy conductPolicy,
            InterviewReplyParser replyParser,
            InterviewTopicCoverage topicCoverage,
            PromptInjectionGuard injectionGuard,
            InterviewTemplateService templateService,
            com.rightpath.repository.UsersRepository usersRepository) {
        this.scheduleRepo = scheduleRepo;
        this.entryRepository = entryRepository;
        this.openAiStreamingService = openAiStreamingService;
        this.contextService = contextService;
        this.textToSpeechService = textToSpeechService;
        this.toneAnalysisService = toneAnalysisService;
        this.evaluationService = evaluationService;
        this.performanceAnalyzer = performanceAnalyzer;
        this.messagingTemplate = messagingTemplate;
        this.transactionTemplate = transactionTemplate;
        this.conductPolicy = conductPolicy;
        this.replyParser = replyParser;
        this.topicCoverage = topicCoverage;
        this.injectionGuard = injectionGuard;
        this.templateService = templateService;
        this.usersRepository = usersRepository;
    }

    /**
     * The interview being started.
     *
     * <p>The candidate's choice where they made one. They pick from a list of
     * everything outstanding, and more than one can be — a repeat L2 alongside
     * an L3 — so falling back to the most recently assigned schedule started
     * whichever the administrator happened to book last rather than the one the
     * candidate clicked.</p>
     *
     * <p>The id is checked against their own email rather than trusted. It
     * arrives from the browser and schedule ids are sequential: without the
     * check, editing one number would start somebody else's interview and write
     * answers into their transcript.</p>
     */
    private CandidateInterviewSchedule resolveSchedule(String jobPrefix, String email, Long scheduleId) {
        if (scheduleId != null) {
            return scheduleRepo.findById(scheduleId)
                    .filter(s -> s.getEmail() != null && s.getEmail().equalsIgnoreCase(email))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Interview " + scheduleId + " was not found for " + email));
        }
        return scheduleRepo.findFirstByJobPrefixAndEmailOrderByAssignedAtDesc(jobPrefix, email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview schedule not found for " + jobPrefix + " / " + email));
    }


    /**
     * The greeting that opens every interview.
     *
     * <p>Addressed to the candidate by name where one is on file. It is the
     * first thing they hear, and being greeted as nobody in particular sets the
     * wrong tone for something that is otherwise a conversation. Falls back to
     * an unnamed greeting rather than guessing from the email address, which
     * would produce "Hello, rohith.mamidala".</p>
     */
    private String buildWelcome(CandidateInterviewSchedule schedule) {
        String interviewer = schedule.getInterviewerName();
        String greeting = candidateFirstName(schedule.getEmail())
                .map(name -> "Hello " + name + ", and welcome.")
                .orElse("Hello, and welcome.");

        return greeting + " I'm "
                + (interviewer == null || interviewer.isBlank() ? "your interviewer" : interviewer)
                + ", and I'll be taking your interview today. Answer in your own words, and take a moment to think"
                + " before you speak if you need to. Let's begin.";
    }

    /** The candidate's first name, if their account carries one. */
    private java.util.Optional<String> candidateFirstName(String email) {
        if (email == null || email.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return usersRepository.findByEmail(email)
                    .map(com.rightpath.entity.Users::getFirstName)
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim);
        } catch (Exception e) {
            // A greeting is never worth failing an interview over.
            log.warn("Could not read the candidate's name for {}", email, e);
            return java.util.Optional.empty();
        }
    }

    /**
     * Whether an interview may be started at this moment.
     *
     * <p>The window's start and end are wall-clock times the recruiter picked, in the business time zone, and
     * {@code now} has to be read in that same zone. They were compared with the server's own clock instead, so
     * on a server running in UTC a slot booked for 11:23 in India stayed "not opened yet" for another five and a
     * half hours, and a deadline ran the same distance late.</p>
     */
    static void requireWithinWindow(CandidateInterviewSchedule schedule, LocalDateTime now) {
        // An interview assigned before the deadline was mandatory has none, and is open until it is sat.
        if (schedule.getDeadlineTime() != null && schedule.getDeadlineTime().isBefore(now)) {
            throw new IllegalStateException(
                    "The time allowed for this interview has passed. Ask the recruiter to schedule it again.");
        }
        // The window opens at the start time the recruiter chose. A minute of grace for clock differences.
        if (schedule.getAssignedAt() != null && schedule.getAssignedAt().isAfter(now.plusMinutes(1))) {
            throw new IllegalStateException("This interview has not opened yet. It opens on "
                    + schedule.getAssignedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"))
                    + ".");
        }
    }

    /**
     * Greeting followed by the first question, with any type tag kept in front.
     *
     * <p>The tag has to lead the message: the frontend opens the code editor
     * from it, and a {@code [CODING]} buried after the greeting is text the
     * candidate reads rather than a signal the client acts on.</p>
     */
    private String mergeWelcomeWithQuestion(String welcome, String question) {
        java.util.regex.Matcher tag = QUESTION_TYPE_TAG_PATTERN.matcher(question);
        if (tag.find()) {
            String marker = tag.group().trim();
            String withoutTag = question.substring(0, tag.start()) + question.substring(tag.end());
            return marker + " " + welcome + "\n\n" + withoutTag.trim();
        }
        return welcome + "\n\n" + question;
    }

    /**
     * Strip [CODING], [THEORY], [NON-TECH] tags from text (used before TTS and DB save).
     */
    private String stripQuestionTypeTags(String text) {
        if (text == null) return null;
        return QUESTION_TYPE_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }

//    @Override
//    @Transactional
//    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email) {
//        CandidateInterviewSchedule schedule = scheduleRepo.findFirstByJobPrefixAndEmailOrderByAssignedAtDesc(jobPrefix, email)
//                .orElseThrow(() -> new RuntimeException("Interview schedule not found for " + jobPrefix + " / " + email));
//
//        // Item 4: If already IN_PROGRESS, return existing state instead of creating new
//        if (schedule.getAttemptStatus() == AttemptStatus.IN_PROGRESS) {
//            log.info("Resuming existing voice interview for schedule {} ({})", schedule.getId(), email);
//            List<VoiceConversationEntry> entries = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId());
//            String lastQuestion = entries.stream()
//                    .filter(e -> e.getRole() == ConversationRole.INTERVIEWER)
//                    .reduce((first, second) -> second)
//                    .map(VoiceConversationEntry::getContent)
//                    .orElse("Welcome back. Let's continue the interview.");
//
//            String lastQuestionAudio = textToSpeechService.generateTTSBase64(lastQuestion);
//
//            return VoiceStartResponse.builder()
//                    .scheduleId(schedule.getId())
//                    .firstQuestion(lastQuestion)
//                    .interviewerName(schedule.getInterviewerName())
//                    .firstQuestionAudio(lastQuestionAudio)
//                    .build();
//        }
//
//        schedule.setAttemptStatus(AttemptStatus.IN_PROGRESS);
//        schedule.setStartedAt(LocalDateTime.now());
//        schedule.setTotalQuestionsAsked(0);
//        schedule = scheduleRepo.save(schedule);
//
//        // Generate first question using DB system prompt
//        String firstQuestionPrompt = contextService.buildFirstQuestionPrompt();
//        List<Map<String, String>> messages = contextService.buildContextMessages(schedule, firstQuestionPrompt);
//        String firstQuestion = openAiStreamingService.chatCompletion(messages);
//
//        // Strip question type tags for TTS and DB (frontend detects tags from raw text)
//        String firstQuestionClean = stripQuestionTypeTags(firstQuestion);
//
//        // Save interviewer's first question (clean, without tags)
//        VoiceConversationEntry entry = VoiceConversationEntry.builder()
//                .interviewSchedule(schedule)
//                .role(ConversationRole.INTERVIEWER)
//                .content(firstQuestionClean)
//                .build();
//        entryRepository.save(entry);
//        schedule.incrementQuestionsAsked();
//        scheduleRepo.save(schedule);
//
//        // Generate TTS for first question (clean, without tags)
//        String firstQuestionAudio = textToSpeechService.generateTTSBase64(firstQuestionClean);
//
//        log.info("Started voice interview for schedule {} ({})", schedule.getId(), email);
//
//        return VoiceStartResponse.builder()
//                .scheduleId(schedule.getId())
//                .firstQuestion(firstQuestion)
//                .interviewerName(schedule.getInterviewerName())
//                .firstQuestionAudio(firstQuestionAudio)
//                .build();
//    }
    
    @Override
    @Transactional
    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate) {
        return startVoiceInterview(jobPrefix, email, fromDate, toDate, null);
    }

    @Override
    @Transactional
    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate,
            Long scheduleId) {
        CandidateInterviewSchedule schedule = resolveSchedule(jobPrefix, email, scheduleId);

        // Resume, rather than silently restart.
        //
        // This branch was an empty placeholder, so an interview already under
        // way fell through to the code below: the question count went back to
        // zero while the turns already recorded stayed where they were. The
        // model was then handed a transcript of the earlier attempt and opened
        // with "let's start again", and the budget no longer matched the
        // record — 20 more questions on top of however many had been asked.
        //
        // Resuming hands back the question they were on and leaves the
        // transcript and the count exactly as they are.
        if (schedule.getAttemptStatus() == AttemptStatus.IN_PROGRESS) {
            log.info("Resuming interview {} for {}", schedule.getId(), email);

            String question = resumeQuestion(schedule);

            return VoiceStartResponse.builder()
                    .scheduleId(schedule.getId())
                    .firstQuestion(question)
                    .interviewerName(schedule.getInterviewerName())
                    .firstQuestionAudio(isCodeExplanation(question)
                            ? null
                            : textToSpeechService.generateTTSBase64(stripQuestionTypeTags(question)))
                    .resumed(true)
                    .questionsAsked(schedule.getTotalQuestionsAsked())
                    // The original deadline, not a fresh one. A refresh used
                    // to restart the browser's countdown from the full
                    // duration, so reloading was a way to buy another hour.
                    .expiresAt(expiryOf(schedule))
                    .build();
        }

        // One assigned with no deadline (before it was mandatory) is open until it is sat; the window check below
        // only applies the end time where there is one.
        requireWithinWindow(schedule, businessSchedule.now());

        // Start a new interview
        schedule.setAttemptStatus(AttemptStatus.IN_PROGRESS);
        schedule.setStartedAt(LocalDateTime.now());
        schedule.setTotalQuestionsAsked(0);
        // Reset alongside the turn count. A re-sit that kept the old counters
        // would open with its probe allowance already spent and its question
        // floor already met.
        schedule.setDistinctQuestionsAsked(0);
        schedule.setFollowUpsOnCurrentQuestion(0);
        schedule.setRephrasesOnCurrentQuestion(0);

        // ✅ Store the date filters in the schedule (if provided)
        if (fromDate != null) {
            schedule.setQuestionsFromDate(Instant.ofEpochMilli(fromDate).atZone(ZoneOffset.UTC).toLocalDateTime());
        }
        if (toDate != null) {
            schedule.setQuestionsToDate(Instant.ofEpochMilli(toDate).atZone(ZoneOffset.UTC).toLocalDateTime());
        }
        
        schedule = scheduleRepo.save(schedule);

        // The first question is generated, not read from a list.
        //
        // This used to call prepareQuestionsAndCreateSession, which loaded an
        // uploaded question file and walked it by index for the whole interview
        // — so every candidate on a job got the same questions in the same
        // order, and a job with no file uploaded could not interview at all.
        // The model now writes each question from the conversation so far,
        // through the same context path every later turn uses.
        String generatedQuestion = openAiStreamingService.chatCompletion(
                contextService.buildContextMessages(schedule, contextService.buildFirstQuestionPrompt()));
        if (generatedQuestion == null || generatedQuestion.isBlank()) {
            String why = openAiStreamingService.lastChatFailure();
            throw new IllegalStateException(
                    "The interviewer could not be reached to start this interview. Please try again."
                            + (why == null ? "" : " (" + why + ")"));
        }

        // Control tags off before anything else touches the text, so the
        // greeting is merged into speech rather than into a tag block.
        ParsedInterviewReply parsedFirst = replyParser.parse(generatedQuestion.trim());

        // The greeting is the one fixed line in the interview. Merged into the
        // first message so the candidate hears it before the question, rather
        // than being greeted by a question.
        String firstQuestionRaw = mergeWelcomeWithQuestion(buildWelcome(schedule), parsedFirst.spokenText());
        String firstQuestionClean = stripQuestionTypeTags(firstQuestionRaw);



        // Save interviewer's first question (clean) to the voice conversation table
        VoiceConversationEntry entry = VoiceConversationEntry.builder()
                .interviewSchedule(schedule)
                .role(ConversationRole.INTERVIEWER)
                .content(firstQuestionClean)
                .topic(parsedFirst.topic())
                .turnKind(TurnKind.NEW_QUESTION)
                .build();
        entryRepository.save(entry);

        schedule.incrementQuestionsAsked();
        schedule.recordNewQuestion();
        scheduleRepo.save(schedule);

        // Generate TTS for the clean question
        String firstQuestionAudio = null;
        if (!isCodeExplanation(firstQuestionRaw)) {
            firstQuestionAudio = textToSpeechService.generateTTSBase64(firstQuestionClean);
        }

        log.info("Started voice interview for schedule {} ({})", schedule.getId(), email);

        return VoiceStartResponse.builder()
                .scheduleId(schedule.getId())
                .firstQuestion(firstQuestionRaw)      // raw with tags for frontend detection
                .interviewerName(schedule.getInterviewerName())
                .firstQuestionAudio(firstQuestionAudio)   // null for code explanation
                // The same instant the server times the interview out against,
                // so the clock on the candidate's screen and the one that
                // actually ends the interview cannot disagree.
                .expiresAt(expiryOf(schedule))
                .build();
    }

    @Override
    @Transactional
    public ResumeResponse resumeInterview(Long scheduleId) {
        return scheduleRepo.findByIdAndAttemptStatus(scheduleId, AttemptStatus.IN_PROGRESS)
                .map(schedule -> {
                    List<VoiceConversationEntry> entries = entryRepository
                            .findByInterviewScheduleIdOrderByTimestampAsc(scheduleId);

                    List<ResumeResponse.ConversationEntryDTO> history = entries.stream()
                            .map(e -> ResumeResponse.ConversationEntryDTO.builder()
                                    .role(e.getRole().name().toLowerCase())
                                    .content(e.getContent())
                                    .timestamp(e.getTimestamp().toString())
                                    .build())
                            .toList();

                    return ResumeResponse.builder()
                            .hasActiveSession(true)
                            .scheduleId(scheduleId)
                            .currentQuestionIndex(schedule.getTotalQuestionsAsked())
                            .warningCount(schedule.getWarningCount())
                            .conversationHistory(history)
                            .build();
                })
                .orElse(ResumeResponse.builder().hasActiveSession(false).build());
    }

//    @Override
//    public void processVoiceAnswer(Long scheduleId, VoiceAnswerRequest request) {
//        try {
//            boolean skipped = request.isSkipped();
//
//            // ── Phase 1: Short TX (~50ms) — Validate + Save candidate answer ──
//            CandidateInterviewSchedule schedule = transactionTemplate.execute(status -> {
//                CandidateInterviewSchedule s = scheduleRepo.findById(scheduleId)
//                        .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));
//
//                if (s.getAttemptStatus() != AttemptStatus.IN_PROGRESS) {
//                    throw new RuntimeException("Interview is not active");
//                }
//
//                if (skipped) {
//                    // Save skipped entry — no tone analysis needed
//                    VoiceConversationEntry candidateEntry = VoiceConversationEntry.builder()
//                            .interviewSchedule(s)
//                            .role(ConversationRole.CANDIDATE)
//                            .content("[NO RESPONSE - SKIPPED]")
//                            .build();
//                    entryRepository.save(candidateEntry);
//                } else {
//                    // Analyze tone metrics
//                    double speechDuration = (request.getWordTimestamps() != null && !request.getWordTimestamps().isEmpty())
//                            ? request.getWordTimestamps().get(request.getWordTimestamps().size() - 1).getEnd()
//                            : 0;
//                    ToneAnalysisService.ToneMetrics toneMetrics = toneAnalysisService.analyze(
//                            request.getTranscript(), request.getWordTimestamps(), speechDuration);
//
//                    // Build content — append code block if code was submitted
//                    String answerContent = request.getTranscript();
//                    if (request.getCodeContent() != null && !request.getCodeContent().isBlank()) {
//                        answerContent += "\n\n[CODE (" + (request.getCodeLanguage() != null ? request.getCodeLanguage() : "text") + ")]\n" + request.getCodeContent();
//                    }
//
//                    // Save candidate answer
//                    VoiceConversationEntry candidateEntry = VoiceConversationEntry.builder()
//                            .interviewSchedule(s)
//                            .role(ConversationRole.CANDIDATE)
//                            .content(answerContent)
//                            .wordCount(toneMetrics.getWordCount())
//                            .wordsPerMinute(toneMetrics.getWordsPerMinute())
//                            .fillerWordCount(toneMetrics.getFillerWordCount())
//                            .confidenceScore(toneMetrics.getConfidenceScore())
//                            .speechDurationSeconds(toneMetrics.getSpeechDurationSeconds())
//                            .codeContent(request.getCodeContent())
//                            .codeLanguage(request.getCodeLanguage())
//                            .build();
//                    entryRepository.save(candidateEntry);
//                }
//
//                return s;
//            });
//
//            // Send filler only for real answers (skips don't need "let me think...")
//            if (!skipped) {
//                String filler = FILLERS.get(random.nextInt(FILLERS.size()));
//                sendFiller(scheduleId, filler);
//            }
//
//            // ── Phase 2: No TX — OpenAI calls (3-30s) ──
//
//            // Update running summary (synchronous OpenAI call)
//            String summary = contextService.updateRunningSummary(schedule);
//            if (summary != null) {
//                // Short TX to persist the summary
//                transactionTemplate.execute(new TransactionCallbackWithoutResult() {
//                    @Override
//                    protected void doInTransactionWithoutResult(TransactionStatus status) {
//                        CandidateInterviewSchedule fresh = scheduleRepo.findById(scheduleId)
//                                .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));
//                        fresh.setRunningSummary(summary);
//                        scheduleRepo.save(fresh);
//                    }
//                });
//                // Update local reference for context building
//                schedule.setRunningSummary(summary);
//            }
//
//            // Compute performance snapshot before building prompt (guidance is injected by contextService)
//            PerformanceSnapshot performanceSnapshot = performanceAnalyzer.analyze(schedule);
//
//            // Build prompt — AI decides what to ask next (or end the interview)
//            String userMessage = contextService.buildNextQuestionPrompt(schedule, request.getTranscript(), skipped,
//                    request.getCodeContent(), request.getCodeLanguage());
//            List<Map<String, String>> messages = contextService.buildContextMessages(schedule, userMessage);
//
//            // Stream GPT response
//            openAiStreamingService.streamChatCompletion(messages,
//                    // onToken
//                    token -> {
//                        Map<String, Object> tokenMsg = new HashMap<>();
//                        tokenMsg.put("token", token);
//                        tokenMsg.put("done", false);
//                        messagingTemplate.convertAndSend(
//                                "/topic/interview/" + scheduleId + "/ai-token",
//                                tokenMsg
//                        );
//                    },
//                    // onComplete
//                    completeText -> {
//                        try {
//                            // Check if AI decided to end the interview
//                            boolean isComplete = completeText.contains(INTERVIEW_COMPLETE_MARKER);
//                            String cleanedText = completeText.replace(INTERVIEW_COMPLETE_MARKER, "").trim();
//
//                            // Send fullText WITH tags to frontend (frontend detects [CODING] etc.)
//                            Map<String, Object> doneMsg = new HashMap<>();
//                            doneMsg.put("token", "");
//                            doneMsg.put("done", true);
//                            doneMsg.put("fullText", cleanedText);
//                            messagingTemplate.convertAndSend(
//                                    "/topic/interview/" + scheduleId + "/ai-token",
//                                    doneMsg
//                            );
//
//                            // Strip question type tags for DB and TTS
//                            String textForStorage = stripQuestionTypeTags(cleanedText);
//
//                            // ── Phase 3: Short TX (~50ms) — Save interviewer response ──
//                            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
//                                @Override
//                                protected void doInTransactionWithoutResult(TransactionStatus status) {
//                                    CandidateInterviewSchedule fresh = scheduleRepo.findById(scheduleId)
//                                            .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));
//
//                                    VoiceConversationEntry entry = VoiceConversationEntry.builder()
//                                            .interviewSchedule(fresh)
//                                            .role(ConversationRole.INTERVIEWER)
//                                            .content(textForStorage)
//                                            .build();
//                                    entryRepository.save(entry);
//                                    fresh.incrementQuestionsAsked();
//
//                                    if (isComplete) {
//                                        fresh.setAttemptStatus(AttemptStatus.COMPLETED);
//                                        fresh.setEndedAt(LocalDateTime.now());
//                                        fresh.setCompletionReason(
//                                                performanceSnapshot.isEarlyTerminationSuggested()
//                                                        ? CompletionReason.EARLY_TERMINATION_POOR_PERFORMANCE
//                                                        : CompletionReason.NATURAL_COMPLETION);
//                                    }
//                                    scheduleRepo.save(fresh);
//                                }
//                            });
//
//                            // Send response-complete and TTS (no TX needed)
//                            CandidateInterviewSchedule updated = scheduleRepo.findById(scheduleId)
//                                    .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));
//                            sendResponseComplete(scheduleId, updated, isComplete);
//                            textToSpeechService.generateAndStreamTTS(scheduleId, textForStorage);
//
//                            if (isComplete) {
//                                evaluationService.triggerEvaluationAsync(scheduleId);
//                            }
//                        } catch (Exception e) {
//                            log.error("Error in onComplete callback for schedule {}: {}", scheduleId, e.getMessage(), e);
//                            sendResponseCompleteError(scheduleId);
//                        }
//                    }
//            );
//        } catch (Exception e) {
//            log.error("Error processing voice answer for schedule {}: {}", scheduleId, e.getMessage(), e);
//            sendResponseCompleteError(scheduleId);
//        }
//    }
	
    @Override
    public void processVoiceAnswer(Long scheduleId, VoiceAnswerRequest request) {
        boolean skipped = request.isSkipped();
        
        // --- Phase 1: Save candidate answer (same as before) ---
        CandidateInterviewSchedule schedule = transactionTemplate.execute(status -> {
            CandidateInterviewSchedule s = scheduleRepo.findById(scheduleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
            if (s.getAttemptStatus() != AttemptStatus.IN_PROGRESS) {
                throw new IllegalStateException("Interview is not active");
            }
            if (skipped) {
                VoiceConversationEntry candidateEntry = VoiceConversationEntry.builder()
                        .interviewSchedule(s)
                        .role(ConversationRole.CANDIDATE)
                        .content("[NO RESPONSE - SKIPPED]")
                        .build();
                entryRepository.save(candidateEntry);
            } else {
                double speechDuration = (request.getWordTimestamps() != null && !request.getWordTimestamps().isEmpty())
                        ? request.getWordTimestamps().get(request.getWordTimestamps().size() - 1).getEnd()
                        : 0;
                ToneAnalysisService.ToneMetrics toneMetrics = toneAnalysisService.analyze(
                        request.getTranscript(), request.getWordTimestamps(), speechDuration);
                // Rendered by the shared formatter, so the transcript the evaluator
                // grades is exactly the text the interviewer reacted to — including
                // whether the submitted code was ever run.
                String answerContent = CodingAnswerFormatter.render(
                        request.getTranscript(), request.getCodeContent(),
                        request.getCodeLanguage(), request.getCodeOutput());

                // Neutralised once, here, rather than at each of the four places
                // that later replay a stored answer into a prompt — the rolling
                // context window, the running summary, the evaluation transcript
                // and the reviewer's screen all read from this row, and a guard
                // applied at any one of them leaves the other three open.
                boolean injection = injectionGuard.looksLikeInjection(answerContent);
                if (injection) {
                    injectionGuard.recordAttempt(scheduleId, answerContent);
                }
                answerContent = injectionGuard.stripControlMarkers(answerContent);

                VoiceConversationEntry candidateEntry = VoiceConversationEntry.builder()
                        .interviewSchedule(s)
                        .role(ConversationRole.CANDIDATE)
                        .content(answerContent)
                        .injectionSuspected(injection)
                        .wordCount(toneMetrics.getWordCount())
                        .wordsPerMinute(toneMetrics.getWordsPerMinute())
                        .fillerWordCount(toneMetrics.getFillerWordCount())
                        .confidenceScore(toneMetrics.getConfidenceScore())
                        .speechDurationSeconds(toneMetrics.getSpeechDurationSeconds())
                        .codeContent(request.getCodeContent())
                        .codeLanguage(request.getCodeLanguage())
                        .codeOutput(request.getCodeOutput())
                        .build();
                entryRepository.save(candidateEntry);
            }
            return s;
        });

        if (!skipped) {
            String filler = FILLERS.get(random.nextInt(FILLERS.size()));
            sendFiller(scheduleId, filler);
        }

        // --- Phase 2: Use InterviewService.answer ---
     // --- Phase 2: Use InterviewService.answer ---
        try {
            // Reloaded so the answer just saved is part of the conversation the
            // model is about to read, and so the question count is current.
            CandidateInterviewSchedule current = scheduleRepo.findById(scheduleId).orElseThrow();

            // Every turn goes to the model with the conversation so far — the
            // spoken answer, and any code with its output, exactly as stored.
            // The reply reacts to that answer and asks what comes next, so the
            // interview follows the candidate rather than a fixed list.
            String turnPrompt = contextService.buildNextQuestionPrompt(
                    current,
                    skipped ? "" : request.getTranscript(),
                    skipped,
                    request.getCodeContent(),
                    request.getCodeLanguage());
            String reply = openAiStreamingService.chatCompletion(
                    contextService.buildContextMessages(current, turnPrompt));

            if (reply == null || reply.isBlank()) {
                log.error("Empty reply from the model for schedule {}", scheduleId);
                sendResponseComplete(scheduleId,
                        "Sorry, I did not catch that. Could you answer again?", false);
                return;
            }

            ParsedInterviewReply parsed = replyParser.parse(reply);

            // Closes when the model says it is done, or when the budget is
            // spent. The ceiling is enforced here rather than trusted to the
            // prompt: an interview that never ends is worse than one cut short.
            EffectiveInterviewTemplate template = templateService.resolve(current);
            boolean ceilingHit = conductPolicy.hasReachedCeiling(template, current.getTotalQuestionsAsked());
            boolean finished = parsed.closing() || ceilingHit;

            // A close is held back while material topics are still unasked and
            // there is budget to ask them. The model decides it has seen enough
            // from the last few answers, which is not the same as having covered
            // the syllabus the grader is about to score against — it can only
            // see the tail of the conversation verbatim. Never overridden once
            // the ceiling is in sight: running out of questions mid-syllabus is
            // a configuration problem, and holding the candidate longer does not
            // fix it.
            if (parsed.closing() && !ceilingHit) {
                int remaining = template.maxQuestions() - current.getTotalQuestionsAsked();
                if (!topicCoverage.mayCloseOnCoverage(current, remaining)) {
                    ParsedInterviewReply redirected = askForOutstandingTopic(current);
                    if (redirected != null) {
                        parsed = redirected;
                        finished = false;
                    }
                }
            }

            String spoken = parsed.spokenText();
            ParsedInterviewReply turn = parsed;
            boolean completed = finished;

            transactionTemplate.execute(status -> {
                CandidateInterviewSchedule s = scheduleRepo.findById(scheduleId).orElseThrow();

                // The rating arrives one turn late: the model judges an answer in
                // the reply it gives to it. Written back onto the answer it is
                // about, so difficulty and the reviewer both read it where it
                // belongs rather than off the question that followed.
                if (turn.hasScore() && !skipped) {
                    applyScoreToLastAnswer(s.getId(), turn.answerScore());
                }

                entryRepository.save(VoiceConversationEntry.builder()
                        .interviewSchedule(s)
                        .role(ConversationRole.INTERVIEWER)
                        .content(spoken)
                        .topic(turn.hasTopic() ? turn.topic() : null)
                        .turnKind(turn.turnKind())
                        .build());

                if (completed) {
                    s.setAttemptStatus(AttemptStatus.COMPLETED);
                    s.setEndedAt(LocalDateTime.now());
                    // The ordinary ending, and the only one that was never
                    // recorded. A candidate ending it early, a proctoring
                    // violation and a timeout all stamped a reason; an
                    // interview that simply finished stamped nothing, so the
                    // commonest outcome of all reached the reviewer's screen
                    // as "how this interview ended was not recorded".
                    s.setCompletionReason(CompletionReason.NATURAL_COMPLETION);
                } else {
                    // Every turn spends from the ceiling, probes included —
                    // otherwise an interview could be stretched indefinitely by
                    // calling each turn a follow-up. Only a new question counts
                    // towards the floor, because ten follow-ups on one question
                    // is not ten questions' worth of evidence.
                    s.incrementQuestionsAsked();
                    recordTurnKind(s, turn.turnKind());
                }
                scheduleRepo.save(s);
                return null;
            });

            sendResponseComplete(scheduleId, spoken, finished, parsed.closing() ? "ai_closed" : "question_limit");

            if (!isCodeExplanation(spoken)) {
                textToSpeechService.generateAndStreamTTS(scheduleId, stripQuestionTypeTags(spoken));
            }

            if (finished) {
                // Scores the whole transcript, code and all.
                evaluationService.triggerEvaluationAsync(scheduleId);
            } else {
                // Keeps the rolling summary in step, so context that drops out
                // of the verbatim window is not simply lost.
                contextService.updateRunningSummary(
                        scheduleRepo.findById(scheduleId).orElseThrow());
            }

        } catch (Exception e) {
            log.error("Error generating the next interview turn for schedule {}: {}",
                    scheduleId, e.getMessage(), e);
            sendResponseComplete(scheduleId,
                    "Sorry, something went wrong on my end. Could you answer again?", false);
        }
    }
    /**
     * When a timed-out interview should be recorded as having ended.
     *
     * <p>Its start plus the configured limit. The query that found it only
     * returns interviews already past that point, so this is never in the
     * future. Falls back to the current time for a row with no start recorded,
     * which cannot be placed any better.</p>
     */
    /**
     * When a timed-out interview actually stopped: the last thing said in it, when
     * that is earlier than the time limit.
     *
     * <p>A candidate who closed the tab after twenty minutes was recorded as having
     * sat the full sixty, because the limit was used as the end. That made
     * "time in interview" the length of the allowance rather than the length of the
     * interview. The limit is still the ceiling: nothing can have gone on past it.</p>
     */
    private LocalDateTime endOfActivity(CandidateInterviewSchedule schedule) {
        LocalDateTime limit = expiryOf(schedule);
        try {
            List<VoiceConversationEntry> entries =
                    entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId());
            if (!entries.isEmpty()) {
                LocalDateTime last = entries.get(entries.size() - 1).getTimestamp();
                if (last != null && schedule.getStartedAt() != null
                        && !last.isBefore(schedule.getStartedAt()) && last.isBefore(limit)) {
                    return last;
                }
            }
        } catch (Exception e) {
            log.warn("Could not read the last activity of interview {}; using the time limit", schedule.getId(), e);
        }
        return limit;
    }

    private LocalDateTime expiryOf(CandidateInterviewSchedule schedule) {
        LocalDateTime startedAt = schedule.getStartedAt();
        return startedAt == null ? LocalDateTime.now() : startedAt.plusMinutes(maxDurationMinutes);
    }

    /**
     * The question a resumed interview should put to the candidate.
     *
     * <p>Which one that is depends on where the connection died, and the two
     * cases are not the same question:</p>
     *
     * <ul>
     *   <li><b>Interrupted while thinking.</b> The last thing recorded is the
     *       interviewer's question, unanswered. Ask it again — the candidate
     *       has just come back to a screen with no context and needs to hear
     *       what they are answering.</li>
     *   <li><b>Interrupted between answering and being asked the next.</b> The
     *       answer is saved; the reply to it was lost in flight, because the
     *       model call and the socket send both happen after the answer is
     *       committed. Replaying the last interviewer turn here would ask a
     *       question they have already answered, and the transcript would carry
     *       it twice — so the next question is generated now, from a
     *       conversation that already contains their answer.</li>
     * </ul>
     */
    private String resumeQuestion(CandidateInterviewSchedule schedule) {
        List<VoiceConversationEntry> entries = entryRepository
                .findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId());

        VoiceConversationEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);

        if (last != null && last.getRole() == ConversationRole.CANDIDATE) {
            String generated = generateTurnAfterLostReply(schedule);
            if (generated != null) {
                return generated;
            }
            // The model could not be reached. Falling back to the outstanding
            // question is better than failing the resume outright: the
            // candidate answers it again, which is repetitive but recoverable.
            log.warn("Could not generate the missing turn for interview {}; replaying the last question",
                    schedule.getId());
        }

        return entries.stream()
                .filter(entry -> entry.getRole() == ConversationRole.INTERVIEWER)
                .reduce((first, second) -> second)
                .map(VoiceConversationEntry::getContent)
                .orElse("Welcome back. Let's carry on where we left off.");
    }

    /**
     * Produces and records the turn that was lost, or null if it cannot be.
     *
     * <p>Goes through the same context path a normal turn uses, so the reply
     * reacts to the answer already stored and the tags on it are read and
     * counted exactly as they would have been.</p>
     */
    private String generateTurnAfterLostReply(CandidateInterviewSchedule schedule) {
        try {
            String reply = openAiStreamingService.chatCompletion(
                    contextService.buildContextMessages(schedule,
                            "The candidate's last answer is recorded above, but your reply to it was lost before "
                                    + "they heard it. Pick the conversation up from there: acknowledge their answer "
                                    + "briefly and ask what comes next. Do not repeat a question they have already "
                                    + "answered, and do not mention the interruption."));
            if (reply == null || reply.isBlank()) {
                return null;
            }

            ParsedInterviewReply parsed = replyParser.parse(reply);
            if (parsed.hasScore()) {
                applyScoreToLastAnswer(schedule.getId(), parsed.answerScore());
            }
            entryRepository.save(VoiceConversationEntry.builder()
                    .interviewSchedule(schedule)
                    .role(ConversationRole.INTERVIEWER)
                    .content(parsed.spokenText())
                    .topic(parsed.hasTopic() ? parsed.topic() : null)
                    .turnKind(parsed.turnKind())
                    .build());
            schedule.incrementQuestionsAsked();
            recordTurnKind(schedule, parsed.turnKind());
            scheduleRepo.save(schedule);

            return parsed.spokenText();
        } catch (Exception e) {
            log.warn("Could not rebuild the lost turn for interview {}", schedule.getId(), e);
            return null;
        }
    }

    /**
     * Puts the rating the model just gave onto the answer it was rating.
     *
     * <p>Best effort. A rating that cannot be attached is a slightly worse
     * difficulty signal on the next turn; failing the turn over it would cost
     * the candidate an answer.</p>
     */
    private void applyScoreToLastAnswer(Long scheduleId, Integer score) {
        try {
            entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId).stream()
                    .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                    .reduce((first, second) -> second)
                    .ifPresent(last -> {
                        last.setAnswerScore(score);
                        entryRepository.save(last);
                    });
        } catch (Exception e) {
            log.warn("Could not attach the answer score for interview {}", scheduleId, e);
        }
    }

    /**
     * Spends the turn against the right allowance.
     *
     * <p>A probe past its budget is counted as a new question rather than
     * refused. The model has already written the turn and the candidate is about
     * to hear it — throwing it away would cost them a real question — but
     * letting it go uncounted would mean the allowance never runs out and the
     * interview could sit on one topic to the ceiling. Counting it clears the
     * probe budget and moves the floor, which is the honest reading: the model
     * chose to stay on this ground, so this is the question now.</p>
     */
    private void recordTurnKind(CandidateInterviewSchedule schedule, TurnKind kind) {
        switch (kind) {
            case FOLLOW_UP -> {
                if (conductPolicy.remainingFollowUps(schedule) > 0) {
                    schedule.recordFollowUp();
                } else {
                    log.debug("Follow-up budget spent on interview {}; counting the turn as a new question",
                            schedule.getId());
                    schedule.recordNewQuestion();
                }
            }
            case REPHRASE -> {
                if (conductPolicy.mayRephrase(schedule)) {
                    schedule.recordRephrase();
                } else {
                    log.debug("Rephrase budget spent on interview {}; counting the turn as a new question",
                            schedule.getId());
                    schedule.recordNewQuestion();
                }
            }
            case NEW_QUESTION -> schedule.recordNewQuestion();
        }
    }

    /**
     * A replacement turn when a close was held back for topic coverage.
     *
     * <p>Costs one extra model call, on the rare turn where the interviewer
     * tried to finish with material topics unasked. The reply already in hand is
     * a farewell and cannot be reused for anything else, so there is nothing to
     * salvage from it.</p>
     *
     * <p>Returns null if the second call fails or comes back closing anyway. The
     * caller then lets the original close stand — an interview that ends a
     * little early is a far better outcome than one stuck in a loop refusing to
     * end.</p>
     */
    private ParsedInterviewReply askForOutstandingTopic(CandidateInterviewSchedule schedule) {
        java.util.List<String> outstanding = topicCoverage.outstanding(schedule);
        if (outstanding.isEmpty()) {
            return null;
        }
        try {
            log.info("Holding the close on interview {}: {} topic(s) still unasked — {}",
                    schedule.getId(), outstanding.size(), String.join(", ", outstanding));

            String reply = openAiStreamingService.chatCompletion(
                    contextService.buildContextMessages(
                            schedule, topicCoverage.buildCoverageDirective(outstanding)));
            if (reply == null || reply.isBlank()) {
                return null;
            }
            ParsedInterviewReply parsed = replyParser.parse(reply);
            // Still trying to close, after being told not to. Taking no for an
            // answer here is what stops this becoming a loop.
            return parsed.closing() ? null : parsed;
        } catch (Exception e) {
            log.warn("Could not redirect interview {} onto an outstanding topic", schedule.getId(), e);
            return null;
        }
    }

    @Override
    @Transactional
    public void endVoiceInterview(Long scheduleId) {
        endVoiceInterview(scheduleId, CompletionReason.CANDIDATE_ENDED);
    }

    @Override
    @Transactional
    public void endVoiceInterview(Long scheduleId, CompletionReason reason) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));

        if (reason != CompletionReason.CANDIDATE_ENDED && schedule.getAttemptStatus() == AttemptStatus.COMPLETED) {
            return;
        }

        schedule.setAttemptStatus(AttemptStatus.COMPLETED);
        schedule.setEndedAt(LocalDateTime.now());
        schedule.setCompletionReason(reason);
        scheduleRepo.save(schedule);

        // Fire-and-forget async evaluation generation, once this has committed.
        // Started from inside the transaction it could read the schedule before
        // COMPLETED was visible — and then save that older state back.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evaluationService.triggerEvaluationAsync(scheduleId);
                        }
                    });
        } else {
            evaluationService.triggerEvaluationAsync(scheduleId);
        }

        log.info("Voice interview {} ended", scheduleId);
    }

    @Override
    @Transactional
    public boolean handleWarning(Long scheduleId) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));

        schedule.addWarning();

        boolean overThreshold = schedule.getWarningCount() >= maxWarnings;

        if (overThreshold && !terminateOnMaxWarnings) {
            // Flagged, not failed. The count still means something — it is
            // the reason a person should watch this recording before acting
            // on the result — but the machine does not get to decide it.
            if (!schedule.isNeedsHumanReview()) {
                log.warn("Schedule {} passed {} proctoring warnings; flagged for human review",
                        scheduleId, maxWarnings);
            }
            schedule.setNeedsHumanReview(true);
        }

        scheduleRepo.save(schedule);

        if (overThreshold && terminateOnMaxWarnings) {
            schedule.setAttemptStatus(AttemptStatus.COMPLETED);
            schedule.setInterviewResult(InterviewResult.FAILED);
            schedule.setEndedAt(LocalDateTime.now());
            schedule.setCompletionReason(CompletionReason.PROCTORING_VIOLATION);
            scheduleRepo.save(schedule);
            log.warn("Schedule {} terminated due to excessive warnings", scheduleId);
            return true;
        }

        return false;
    }

    @Override
    public VoiceSessionStatus getSessionStatus(Long scheduleId) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));

        return VoiceSessionStatus.builder()
                .scheduleId(schedule.getId())
                .status(schedule.getAttemptStatus().name())
                .totalQuestionsAsked(schedule.getTotalQuestionsAsked())
                .warningCount(schedule.getWarningCount())
                .startedAt(schedule.getStartedAt())
                .interviewerName(schedule.getInterviewerName())
                .build();
    }

    @Override
    public VoiceEvaluationResult getEvaluation(Long scheduleId) {
        return evaluationService.evaluateInterview(scheduleId);
    }

    /**
     * Item 7: Backend interview timeout enforcement.
     * Runs every 5 minutes to find and auto-complete stale IN_PROGRESS interviews.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void enforceInterviewTimeouts() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxDurationMinutes);
        List<CandidateInterviewSchedule> staleInterviews =
                scheduleRepo.findByAttemptStatusAndStartedAtBefore(AttemptStatus.IN_PROGRESS, cutoff);

        for (CandidateInterviewSchedule schedule : staleInterviews) {
            log.warn("Auto-completing stale interview {} (started at {})", schedule.getId(), schedule.getStartedAt());
            schedule.setAttemptStatus(AttemptStatus.COMPLETED);
            // The moment it was actually over, not the moment this sweep
            // happened to notice.
            //
            // This stamped `now`, so the recorded length was however long the
            // row sat unclosed — the sweep runs every five minutes, but an
            // application restart or an overnight outage leaves it far longer.
            // One real interview was recorded as 774 minutes: an hour of
            // interview and twelve hours of nobody looking. The duration is
            // read as "time in interview" on the result screen and summed
            // across sittings, so a single stale row made the whole candidate
            // look absurd.
            schedule.setEndedAt(endOfActivity(schedule));
            schedule.setCompletionReason(CompletionReason.TIMEOUT);
            scheduleRepo.save(schedule);

            // Try to generate evaluation
            try {
                evaluationService.triggerEvaluationAsync(schedule.getId());
            } catch (Exception e) {
                log.error("Failed to trigger evaluation for timed-out interview {}", schedule.getId(), e);
            }

            // Notify client if still connected
            messagingTemplate.convertAndSend(
                    "/topic/interview/" + schedule.getId() + "/response-complete",
                    Map.of("isComplete", true, "terminated", true, "reason", "Interview timed out")
            );
        }
    }

    private void saveInterviewerResponse(CandidateInterviewSchedule schedule, String response) {
        VoiceConversationEntry entry = VoiceConversationEntry.builder()
                .interviewSchedule(schedule)
                .role(ConversationRole.INTERVIEWER)
                .content(response)
                .build();
        entryRepository.save(entry);
        schedule.incrementQuestionsAsked();
        scheduleRepo.save(schedule);
    }

    private void sendFiller(Long scheduleId, String filler) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("text", filler);
        msg.put("type", "filler");
        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/filler", msg);
    }
//
//    private void sendResponseComplete(Long scheduleId, CandidateInterviewSchedule schedule, boolean isComplete) {
//        sendResponseComplete(scheduleId, schedule, isComplete, false);
//    }
//
//    private void sendResponseComplete(Long scheduleId, CandidateInterviewSchedule schedule, boolean isComplete, boolean terminated) {
//        Map<String, Object> msg = new HashMap<>();
//        msg.put("questionsAsked", schedule.getTotalQuestionsAsked());
//        msg.put("isComplete", isComplete);
//        msg.put("terminated", terminated);
//        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/response-complete", msg);
//    }
//
//    private void sendResponseCompleteError(Long scheduleId) {
//        Map<String, Object> msg = new HashMap<>();
//        msg.put("questionsAsked", -1);
//        msg.put("isComplete", false);
//        msg.put("error", true);
//        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/response-complete", msg);
//    }
    
 // Send response using a String (fetches schedule from DB)
    private void sendResponseComplete(Long scheduleId, String response, boolean isComplete, String endReason) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
        sendResponseComplete(scheduleId, schedule, response, isComplete, endReason);
    }

    private void sendResponseComplete(Long scheduleId, String response, boolean isComplete) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
        sendResponseComplete(scheduleId, schedule, response, isComplete);
    }

    // Send response using an existing schedule object (avoids extra DB query)
    private void sendResponseComplete(Long scheduleId, CandidateInterviewSchedule schedule, String response, boolean isComplete) {
        sendResponseComplete(scheduleId, schedule, response, isComplete, null);
    }

    private void sendResponseComplete(Long scheduleId, CandidateInterviewSchedule schedule, String response, boolean isComplete, String endReason) {
        Map<String, Object> message = new HashMap<>();
        if (endReason != null && isComplete) {
            message.put("reason", endReason);
        }
        message.put("response", response);
        message.put("questionsAsked", schedule.getTotalQuestionsAsked());
        message.put("isComplete", isComplete);
        message.put("terminated", false);
        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/response-complete", message);
    }

    // Keep the original method if it exists – you may adjust it to call the new one
    private void sendResponseComplete(Long scheduleId, CandidateInterviewSchedule schedule, boolean isComplete) {
        sendResponseComplete(scheduleId, schedule, null, isComplete);  // or build a default message
    }

	@Override
	public VoiceStartResponse startVoiceInterview(String jobPrefix, String email) {
		// TODO Auto-generated method stub
		return null;
	}
	
	private boolean isCodeExplanation(String questionText) {
        return questionText != null && questionText.contains("[CODE_EXPLANATION]");
    }
}
