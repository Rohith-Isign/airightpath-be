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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.dto.voice.ParsedInterviewReply;
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
	
    private static final Logger log = LoggerFactory.getLogger(VoiceInterviewServiceImpl.class);

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

    /** Per-interview counters shared across pods: the language of the answer being recorded, the early stop. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rightpath.service.state.SharedStateStore sharedState;

    /** Most an answer to a coding question can be rated when no code was written (spoken explanation only). */
    @org.springframework.beans.factory.annotation.Value("${interview.coding.spoken-only-max-score:3}")
    private int spokenOnlyCodingMax = 3;

    /** Ends an interview early once the candidate is plainly not answering correctly (see shouldStopEarly). */
    @org.springframework.beans.factory.annotation.Value("${interview.early-stop.enabled:true}")
    private boolean earlyStopEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${interview.early-stop.min-rated-answers:8}")
    private int earlyStopMinAnswers = 8;

    @org.springframework.beans.factory.annotation.Value("${interview.early-stop.window:6}")
    private int earlyStopWindow = 6;

    @org.springframework.beans.factory.annotation.Value("${interview.early-stop.max-average:3.0}")
    private double earlyStopMaxAverage = 3.0;

    /** Distinct coding tasks an interview must include before it may close (re-asked tasks do not count again). */
    @org.springframework.beans.factory.annotation.Value("${interview.coding.min-tasks:2}")
    private int minCodingTasks = 2;

    /** Put in front of an answer that was not spoken in English, for the interviewer, the grader and the reviewer. */
    static final String NOT_IN_ENGLISH = "[NOT IN ENGLISH";

    /** For noting on the record that the interview was opened in a second window. Field-injected: optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rightpath.repository.ProctoringEventRepository proctoringEventRepository;
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

    /** Judges each rated answer correct or not. Field-injected so the existing constructor is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rightpath.service.InterviewLevelProgression levelProgression;
    private final InterviewReplyParser replyParser;

    /** The zone interview slots are written in; see {@link BusinessSchedule}. */
    @Autowired
    private BusinessSchedule businessSchedule;

    @Autowired
    private com.rightpath.repository.JobApplicationForCandidateRepository jobAppRepo;

    /** One browser tab at a time per interview. */
    @Autowired(required = false)
    private com.rightpath.service.ExamSessionLock examSessionLock;
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

    /**
     * The longest an interview may run, in minutes. 0 (the default) means no limit: an interview ends when the
     * interviewer has covered what it needs to, or when the candidate ends it, never because a clock ran out.
     */
    @Value("${interview.max-duration-minutes:0}")
    private int maxDurationMinutes;

    /**
     * How long an interview left open with nobody in it is kept before it is closed on the candidate's behalf. Only
     * for interviews that were abandoned (a closed tab, a dead connection); it is far longer than any real interview.
     */
    @Value("${interview.abandoned-after-hours:12}")
    private int abandonedAfterHours;

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
    /** Fewer words than this, in answer to "introduce yourself", is a greeting, not an introduction. */
    static final int INTRODUCTION_MIN_WORDS = 20;

    /** How many times a too-short introduction is asked for again before the interview moves on. */
    static final int INTRODUCTION_MAX_REASKS = 2;

    static final List<String> INTRODUCTION_REASKS = List.of(
            "[REPHRASE] [THEORY] Thank you. Before we get into the technical questions, I would like to know you a "
                    + "little better. Could you please introduce yourself properly: what you have studied, the work "
                    + "or internships you have done, and one or two projects you have built, with the technologies "
                    + "you used?",
            "[REPHRASE] [THEORY] I would still like to hear a bit more about you before we begin. A few sentences is "
                    + "enough: tell me about your background, what you have worked on, and the project you are most "
                    + "proud of.");

    /** Words in a spoken answer, counting only those with a letter in them. */
    static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(text.trim().split("\\s+"))
                .filter(word -> word.codePoints().anyMatch(Character::isLetter))
                .count();
    }

    /**
     * The introduction asked again, when the answer to it was too short to be one ("hello", a name, or nothing), and
     * it has been asked again fewer than {@link #INTRODUCTION_MAX_REASKS} times. Null otherwise.
     */
    private String introductionReask(Long scheduleId, String answer) {
        java.util.List<VoiceConversationEntry> history = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(
                scheduleId);
        java.util.List<VoiceConversationEntry> asked = history.stream()
                .filter(e -> e.getRole() == ConversationRole.INTERVIEWER).toList();
        if (asked.isEmpty() || InterviewContextService.questionsAfterIntroduction(history) > 0
                || !asksForIntroduction(asked.get(asked.size() - 1).getContent())) {
            return null;
        }
        int reasked = asked.size() - 1;
        if (reasked >= INTRODUCTION_MAX_REASKS || wordCount(answer) >= INTRODUCTION_MIN_WORDS) {
            return null;
        }
        log.info("Interview {}: the introduction was {} word(s); asking for it again ({} of {})", scheduleId,
                wordCount(answer), reasked + 1, INTRODUCTION_MAX_REASKS);
        return INTRODUCTION_REASKS.get(Math.min(reasked, INTRODUCTION_REASKS.size() - 1));
    }

    /** The opening question when the generated one is not an introduction. */
    static final String INTRODUCTION_QUESTION = "To start, could you please introduce yourself? Tell me about your "
            + "background, what you have studied or worked on, and the projects you are most proud of.";

    /** Whether a question asks the candidate to introduce themselves, rather than starting on a technical topic. */
    static boolean asksForIntroduction(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String text = question.toLowerCase(java.util.Locale.ROOT);
        if (CODING_OPENING.matcher(question).find()) {
            return false;
        }
        return text.contains("introduce yourself") || text.contains("introduction")
                || text.contains("about yourself") || text.contains("tell me about you")
                || text.contains("your background") || text.contains("walk me through your");
    }

    private String mergeWelcomeWithQuestion(String welcome, String question) {
        // The server's welcome is the only greeting. If the model greeted too, take that off so it is not said twice.
        question = com.rightpath.util.GreetingStripper.strip(question);
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

    
    @Override
    @Transactional
    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate) {
        return startVoiceInterview(jobPrefix, email, fromDate, toDate, null);
    }

    @Override
    @Transactional
    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate,
            Long scheduleId) {
        return startVoiceInterview(jobPrefix, email, fromDate, toDate, scheduleId, null);
    }

    @Override
    @Transactional
    public VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate,
            Long scheduleId, String clientSessionId) {
        CandidateInterviewSchedule schedule = resolveSchedule(jobPrefix, email, scheduleId);

        // The tab's own session id, so a reload in the same tab is the same session; a new one otherwise.
        String sessionId = com.rightpath.service.ExamSessionLock.isValidSessionId(clientSessionId)
                ? clientSessionId : java.util.UUID.randomUUID().toString();
        // One tab at a time. The interview could be started in two browsers and run in both: the second start now
        // waits until the first tab has closed (it gives its lease back) or stopped renewing it.
        if (examSessionLock != null
                && !examSessionLock.claim(com.rightpath.service.ExamSessionLock.INTERVIEW, schedule.getId(), sessionId)) {
            log.warn("Interview {} start refused: already open in another browser or tab", schedule.getId());
            throw new IllegalStateException(com.rightpath.service.ExamSessionLock.OPEN_ELSEWHERE);
        }

        // This window becomes the only one that may run the interview. A window that had it open and has since gone
        // quiet is told it has been replaced, and its messages are refused from now on.
        boolean takenOver = schedule.getAttemptStatus() == AttemptStatus.IN_PROGRESS
                && schedule.getActiveSessionId() != null && !schedule.getActiveSessionId().equals(sessionId);
        schedule.setActiveSessionId(sessionId);
        scheduleRepo.save(schedule);
        if (takenOver) {
            log.warn("Interview {} opened in another window; the earlier window is closed", schedule.getId());
            messagingTemplate.convertAndSend("/topic/interview/" + schedule.getId() + "/session-replaced",
                    Map.of("sessionId", sessionId));
            recordSessionTakeover(schedule);
        }

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
                    .sessionId(sessionId)
                    .resumed(true)
                    .questionsAsked(schedule.getTotalQuestionsAsked())
                    // The original deadline, not a fresh one. A refresh used
                    // to restart the browser's countdown from the full
                    // duration, so reloading was a way to buy another hour.
                    .expiresAt(expiryOf(schedule))
                    .startedAt(schedule.getStartedAt())
                    .build();
        }

        // One assigned with no deadline (before it was mandatory) is open until it is sat; the window check below
        // only applies the end time where there is one.
        requireWithinWindow(schedule, businessSchedule.now());

        // Start a new interview
        schedule.setAttemptStatus(AttemptStatus.IN_PROGRESS);
        schedule.setStartedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
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

        // Every interview opens with the candidate introducing themselves. The model was told so, but a later rule
        // ("ask the next skill at Basic") reached it on the first turn too and it opened on a technical question. The
        // opening is checked here, and replaced with the standard one if it is not an introduction.
        if (!asksForIntroduction(parsedFirst.spokenText())) {
            log.info("Interview {}: the first question was not an introduction; using the standard opening",
                    schedule.getId());
            parsedFirst = new ParsedInterviewReply(INTRODUCTION_QUESTION, null, TurnKind.NEW_QUESTION, null, false);
        }

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
                // The opening asks for an introduction, not a technical answer: no category, skill or level.
                .topic(null)
                .level(null)
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
                .sessionId(sessionId)
                .firstQuestion(firstQuestionRaw)      // raw with tags for frontend detection
                .interviewerName(schedule.getInterviewerName())
                .firstQuestionAudio(firstQuestionAudio)   // null for code explanation
                // The same instant the server times the interview out against,
                // so the clock on the candidate's screen and the one that
                // actually ends the interview cannot disagree.
                .expiresAt(expiryOf(schedule))
                .startedAt(schedule.getStartedAt())
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

	
    @Override
    public void processVoiceAnswer(Long scheduleId, VoiceAnswerRequest request) {
        boolean skipped = request.isSkipped();
        // A record of answers that reach the server with nothing in them, for when a candidate says they were not heard.
        if (!skipped && (request.getTranscript() == null || request.getTranscript().isBlank())
                && (request.getCodeContent() == null || request.getCodeContent().isBlank())) {
            log.warn("Interview {}: an answer arrived with no transcript and no code", scheduleId);
        }
        
        // --- Phase 1: Save candidate answer (same as before) ---
        CandidateInterviewSchedule schedule = transactionTemplate.execute(status -> {
            CandidateInterviewSchedule s = scheduleRepo.findById(scheduleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
            if (s.getAttemptStatus() != AttemptStatus.IN_PROGRESS) {
                throw new IllegalStateException("Interview is not active");
            }
            if (skipped) {
                clearLanguageCount(scheduleId);
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

                // Transcription is pinned to English, which translates another language into English: an answer
                // given in Telugu read as a fluent English one and was scored as such. When most of the answer's
                // audio was in another language, the answer says so in front, is scored 0 when rated, and the
                // candidate is asked to answer in English.
                String spokenIn = nonEnglishLanguage(scheduleId);
                if (spokenIn != null) {
                    answerContent = NOT_IN_ENGLISH + ": the candidate answered in " + spokenIn + ". The text below is "
                            + "a machine translation, not their English. It earns no marks, including for "
                            + "communication.]\n" + answerContent;
                    messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/language-warning",
                            Map.of("language", spokenIn));
                }

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
            EffectiveInterviewTemplate template = templateService.resolve(current);

            // Where the closing sequence stands, decided here rather than left to the model. The sequence is a
            // closing question, the candidate's reply, then a final thank-you. The model used to be trusted to follow
            // it from the prompt alone, and it would answer the candidate's question and then carry on with another
            // interview question, or the server would end the interview on the question ceiling in the middle of one.
            boolean closingAsked = closingQuestionAlreadyAsked(scheduleId);
            // This reply answers the closing question, not an interview question: it is never scored.
            boolean answeringClosing = closingAsked;
            // The candidate may ask up to three things at the close. While they are still asking, and under the
            // limit, the interviewer answers and invites the next one; the final thank-you comes when they have
            // none left, or on the third.
            boolean moreQuestionsWelcome = closingAsked && !skipped
                    && looksLikeAQuestion(request.getTranscript())
                    && candidateRepliesToClosing(scheduleId) < MAX_CLOSING_QUESTIONS;
            // "Yes" to "do you have any questions?" is not a question yet: they are about to ask one. It used to
            // count as "no questions" and the interview ended on the goodbye. Now they are invited to go ahead.
            boolean inviteQuestion = closingAsked && !skipped && !moreQuestionsWelcome
                    && saysTheyHaveAQuestion(request.getTranscript())
                    && candidateRepliesToClosing(scheduleId) < MAX_CLOSING_QUESTIONS;
            if (moreQuestionsWelcome || inviteQuestion) {
                moreQuestionsWelcome = true;
                closingAsked = false;
            }
            int turnsLeft = template.effectiveMax() - current.getTotalQuestionsAsked();

            String turnPrompt = contextService.buildNextQuestionPrompt(
                    current,
                    skipped ? "" : request.getTranscript(),
                    skipped,
                    request.getCodeContent(),
                    request.getCodeLanguage());
            if (moreQuestionsWelcome) {
                turnPrompt += "\n\n" + CLOSING_ANSWER_DIRECTIVE;
            } else if (closingAsked) {
                turnPrompt += "\n\n" + FINAL_REPLY_DIRECTIVE;
            } else if (turnsLeft == 1) {
                turnPrompt += "\n\n" + CLOSING_QUESTION_DIRECTIVE;
            } else if (!moreQuestionsWelcome && shouldStopEarly(scheduleId) && everySkillStarted(current)) {
                // The candidate is plainly not answering correctly. Carrying on only lengthens a result that is
                // already clear, so the interview moves to its close, and is recorded as ended for that reason.
                log.info("Interview {}: answers consistently below the mark; moving to close", scheduleId);
                if (sharedState != null) {
                    sharedState.set("early-stop:" + scheduleId, "1", java.time.Duration.ofHours(3));
                }
                turnPrompt += "\n\n" + CLOSING_QUESTION_DIRECTIVE;
            }
            // A greeting is not an introduction. "Hello" sent as the answer to "introduce yourself" was taken as one
            // and the interview went straight on; the introduction is now asked again, up to twice.
            String introductionReask = skipped || !closingAsked && !moreQuestionsWelcome
                    ? introductionReask(scheduleId, skipped ? "" : request.getTranscript()) : null;
            // The invitation is a fixed line, worded so the next reply is still read as part of the close.
            String reply = introductionReask != null ? introductionReask
                    : inviteQuestion
                    ? INVITE_QUESTION
                    : openAiStreamingService.chatCompletion(contextService.buildContextMessages(current, turnPrompt));

            if (reply == null || reply.isBlank()) {
                log.error("Empty reply from the model for schedule {}", scheduleId);
                sendResponseComplete(scheduleId,
                        // Said honestly: this is the interviewer failing to reply, not the candidate's speech going
                        // unheard. "I did not catch that" made candidates think their microphone had failed.
                        "Sorry, I had a technical problem on my side and could not process that. "
                                + "Could you please give your answer again?", false);
                return;
            }

            ParsedInterviewReply parsed = replyParser.parse(reply);

            // A coding question has to come with its examples. The prompt asks for two, but the model leaves them
            // off some problems, and the candidate is then left guessing the input and output they must handle.
            if (isCodingQuestion(parsed) && !hasExamples(parsed.spokenText())) {
                parsed = askForExamples(current, parsed, turnPrompt);
            }

            // A request to fix the code just submitted is a coding turn, whatever tags the model put on it: the
            // notepad has to open, with the candidate's code put back in it, and it is a correction of the same
            // task rather than a new one. It came back untagged, the notepad never opened, and the candidate had
            // nowhere to correct their code.
            if (!parsed.closing() && asksToCorrectCode(parsed.spokenText()) && lastAnswerHadCode(scheduleId)) {
                String text = CODING_OPENING.matcher(parsed.spokenText()).find()
                        ? parsed.spokenText()
                        : InterviewConductPolicy.CODING_TAG + " " + parsed.spokenText();
                TurnKind kind = parsed.turnKind() == TurnKind.NEW_QUESTION ? TurnKind.FOLLOW_UP : parsed.turnKind();
                parsed = new ParsedInterviewReply(text, parsed.answerScore(), kind, parsed.topic(), false,
                        parsed.level(), parsed.codeMismatch(), parsed.skill(), parsed.notEnglish(),
                        parsed.competency());
            }

            // A coding problem is asked once. Once an answer to it has been submitted, it is rated and the interview
            // moves on; asking the same problem again left the candidate re-submitting the same answer in a loop.
            if (isCodingQuestion(parsed) && repeatsEarlierCodingProblem(scheduleId, parsed)) {
                parsed = askForDifferentProblem(current, parsed, turnPrompt);
            }

            // A new question tests something not yet tested for its skill. Asking about the same competency again,
            // reworded, was counted as new coverage when it showed nothing new.
            if (!parsed.closing() && (parsed.turnKind() == null || parsed.turnKind() == TurnKind.NEW_QUESTION)
                    && repeatsCompetency(current, parsed)) {
                parsed = askForDifferentCompetency(current, parsed, turnPrompt);
            }

            // The same question again, by its wording, under whatever competency name. The interviewer sees only the
            // last few exchanges word for word, and asked questions again that had dropped out of its view.
            if (introductionReask == null && !inviteQuestion && !parsed.closing()
                    && (parsed.turnKind() == null || parsed.turnKind() == TurnKind.NEW_QUESTION)) {
                String earlier = InterviewContextService.repeatedQuestion(
                        entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId), parsed.spokenText());
                if (earlier != null) {
                    parsed = askForUnaskedQuestion(current, parsed, turnPrompt, earlier);
                }
            }

            // The resume questions come first and check the resume; they do not count towards a skill's levels, so
            // whatever skill or level the model put on one is taken off.
            boolean resumeQuestion = introductionReask == null && !inviteQuestion && !parsed.closing()
                    && (parsed.turnKind() == null || parsed.turnKind() == TurnKind.NEW_QUESTION)
                    && contextService.inResumePhase(current);
            if (resumeQuestion && (parsed.level() != null || parsed.skill() != null)) {
                parsed = new ParsedInterviewReply(parsed.spokenText(), parsed.answerScore(), parsed.turnKind(),
                        parsed.topic(), false, null, parsed.codeMismatch(), null, parsed.notEnglish(),
                        parsed.competency());
            }

            // The question has to be the one the assessment plan calls for: the skill in progress until it is
            // complete, a new skill at Basic, and the coding task where one is owed. The plan was only an
            // instruction, and the interviewer left Python half-assessed and started Machine Learning at
            // Intermediate. Checked here, and sent back once if it does not match.
            if (current.getEffectiveRound() == com.rightpath.enums.InterviewRound.L2_TECHNICAL
                    && levelProgression != null && !parsed.closing() && !isClosingQuestion(parsed.spokenText())
                    && !moreQuestionsWelcome && !closingAsked && current.getTotalQuestionsAsked() > 1
                    && !resumeQuestion && introductionReask == null && !contextService.inResumePhase(current)
                    && (parsed.turnKind() == null || parsed.turnKind() == TurnKind.NEW_QUESTION)) {
                java.util.List<String> required = contextService.requiredSkills(current);
                com.rightpath.service.InterviewLevelProgression.Expected expected =
                        levelProgression.expectedNext(historyWithScore(scheduleId, parsed.answerScore()), required,
                                template.effectiveMax());
                String problem = planMismatch(expected, parsed, required);
                if (problem != null) {
                    parsed = askToFollowPlan(current, parsed, turnPrompt, problem, expected, required);
                }
            }

            // Was the answer just given not in English? Either the speech check found it (the answer was saved
            // marked), or the interviewer saw it in the text and the text is not plainly English. Decided once
            // here, so the spoken notice, the score and the on-screen alert all agree.
            boolean answerNotEnglish = !skipped && !answeringClosing
                    && (lastAnswerMarkedNotEnglish(scheduleId)
                            || (parsed.notEnglish() && !looksEnglish(request.getTranscript())));
            if (answerNotEnglish && !parsed.closing()) {
                parsed = withEnglishNotice(parsed);
            }

            // Closes when the model says it is done, or when the budget is
            // spent. The ceiling is enforced here rather than trusted to the
            // prompt: an interview that never ends is worse than one cut short.
            boolean ceilingHit = conductPolicy.hasReachedCeiling(template, current.getTotalQuestionsAsked());
            // The candidate has answered the closing question, so this reply is the last one whatever the model says.
            boolean finished = !moreQuestionsWelcome && (parsed.closing() || ceilingHit || closingAsked);

            // A close is held back while material topics are still unasked and
            // there is budget to ask them. The model decides it has seen enough
            // from the last few answers, which is not the same as having covered
            // the syllabus the grader is about to score against — it can only
            // see the tail of the conversation verbatim. Never overridden once
            // the ceiling is in sight: running out of questions mid-syllabus is
            // a configuration problem, and holding the candidate longer does not
            // fix it.
            if (parsed.closing() && !ceilingHit && !closingAsked && !moreQuestionsWelcome) {
                int remaining = template.effectiveMax() - current.getTotalQuestionsAsked();
                if (!topicCoverage.mayCloseOnCoverage(current, remaining)) {
                    ParsedInterviewReply redirected = askForOutstandingTopic(current);
                    if (redirected != null) {
                        parsed = redirected;
                        finished = false;
                    }
                }
            }

            // The completion gate (technical round). The interview may not close, or move to its closing question,
            // until every required skill has been taken through its levels (to Expert, or until it stopped) and the
            // distinct coding tasks are done. It closed before with skills and coding still untested. Not applied
            // when the interview is ending for poor performance or the question budget is nearly spent.
            boolean wantsToClose = (parsed.closing() || isClosingQuestion(parsed.spokenText()))
                    && !ceilingHit && !closingAsked && !moreQuestionsWelcome;
            boolean stoppingEarly = sharedState != null && sharedState.get("early-stop:" + scheduleId).isPresent();
            if (wantsToClose && !stoppingEarly && levelProgression != null && turnsLeft > 2
                    && current.getEffectiveRound() == com.rightpath.enums.InterviewRound.L2_TECHNICAL) {
                String gap = levelProgression.completionGap(
                        entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId),
                        contextService.requiredSkills(current), minCodingTasks, template.effectiveMax());
                if (gap != null) {
                    ParsedInterviewReply continued = askToContinue(current, gap);
                    if (continued != null) {
                        parsed = continued;
                        finished = false;
                    }
                }
            }

            // The interview is about to end, and the candidate's last words from the interviewer must be a goodbye. A
            // forced end (question ceiling) or a reply that closed while still asking something would otherwise show
            // the candidate the "interview finished" screen under an unanswered question.
            if (finished && (!parsed.closing() || parsed.spokenText().contains("?"))) {
                parsed = askForFarewell(current, parsed);
            }

            String spoken = parsed.spokenText();
            ParsedInterviewReply turn = parsed;
            boolean completed = finished;
            java.util.concurrent.atomic.AtomicBoolean mismatchFlagged = new java.util.concurrent.atomic.AtomicBoolean();

            transactionTemplate.execute(status -> {
                CandidateInterviewSchedule s = scheduleRepo.findById(scheduleId).orElseThrow();

                // The rating arrives one turn late: the model judges an answer in
                // the reply it gives to it. Written back onto the answer it is
                // about, so difficulty and the reviewer both read it where it
                // belongs rather than off the question that followed.
                // The interviewer found the answer was not in English, including another language written out in
                // English letters, which the speech check cannot see. Marked, scored 0, and the candidate is told.
                // An answer that is plainly English is not marked otherwise, whatever the tag says: the flag was
                // put on an off-topic English answer and it scored 0 for "not answered in English".
                boolean notEnglish = answerNotEnglish;
                if (turn.notEnglish() && !notEnglish) {
                    log.info("Interview {}: the not-English flag was ignored on an answer that is in English", scheduleId);
                }
                if (notEnglish && !skipped && !answeringClosing) {
                    markLastAnswerNotEnglish(s.getId());
                }
                // Replies to the closing question ("yes", "no questions") are not answers to grade.
                if ((turn.hasScore() || turn.codeMismatch() || notEnglish) && !skipped && !answeringClosing) {
                    mismatchFlagged.set(applyScoreToLastAnswer(s.getId(),
                            notEnglish ? Integer.valueOf(0) : turn.answerScore(), turn.codeMismatch()));
                }

                entryRepository.save(VoiceConversationEntry.builder()
                        .interviewSchedule(s)
                        .role(ConversationRole.INTERVIEWER)
                        .content(spoken)
                        .topic(turn.hasTopic() ? turn.topic() : null)
                        .level(levelOf(turn))
                        .skill(levelOf(turn) == null && turn.closing() ? null
                                : contextService.canonicalSkill(s, turn.skill()))
                        .competency(levelOf(turn) == null ? null : turn.competency())
                        .turnKind(turn.turnKind())
                        .build());

                if (completed) {
                    s.setAttemptStatus(AttemptStatus.COMPLETED);
                    s.setEndedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
                    // The ordinary ending, and the only one that was never
                    // recorded. A candidate ending it early, a proctoring
                    // violation and a timeout all stamped a reason; an
                    // interview that simply finished stamped nothing, so the
                    // commonest outcome of all reached the reviewer's screen
                    // as "how this interview ended was not recorded".
                    boolean stoppedEarly = sharedState != null
                            && sharedState.get("early-stop:" + scheduleId).isPresent();
                    s.setCompletionReason(stoppedEarly
                            ? CompletionReason.EARLY_TERMINATION_POOR_PERFORMANCE
                            : CompletionReason.NATURAL_COMPLETION);
                    advanceApplicationAfterInterview(s);
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

            sendResponseComplete(scheduleId, spoken, finished, parsed.closing() ? "ai_closed" : "question_limit",
                    turn.turnKind(), mismatchFlagged.get(), answerNotEnglish);

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
        LocalDateTime configuredLimit = expiryOf(schedule);
        // With no time limit, the end is the last activity, or now if nothing was said.
        LocalDateTime limit = configuredLimit != null ? configuredLimit : LocalDateTime.now(java.time.ZoneOffset.UTC);
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
        // No limit configured: there is no moment the interview runs out.
        if (maxDurationMinutes <= 0) {
            return null;
        }
        LocalDateTime startedAt = schedule.getStartedAt();
        return startedAt == null ? LocalDateTime.now(java.time.ZoneOffset.UTC) : startedAt.plusMinutes(maxDurationMinutes);
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
            if (parsed.hasScore() || parsed.codeMismatch()) {
                applyScoreToLastAnswer(schedule.getId(), parsed.answerScore(), parsed.codeMismatch());
            }
            entryRepository.save(VoiceConversationEntry.builder()
                    .interviewSchedule(schedule)
                    .role(ConversationRole.INTERVIEWER)
                    .content(parsed.spokenText())
                    .topic(parsed.hasTopic() ? parsed.topic() : null)
                    .level(levelOf(parsed))
                    .skill(parsed.closing() ? null : parsed.skill())
                    .competency(levelOf(parsed) == null ? null : parsed.competency())
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

    /** The language most of the answer just recorded was in, when that was not English; null otherwise. */
    private String nonEnglishLanguage(Long scheduleId) {
        if (sharedState == null) {
            return null;
        }
        try {
            long total = sharedState.get(AudioTranscriptionServiceKeys.total(scheduleId)).map(Long::parseLong).orElse(0L);
            long foreign = sharedState.get(AudioTranscriptionServiceKeys.foreign(scheduleId)).map(Long::parseLong).orElse(0L);
            String name = sharedState.get(AudioTranscriptionServiceKeys.name(scheduleId)).orElse("another language");
            clearLanguageCount(scheduleId);
            return foreign > 0 && foreign * 2 >= total ? capitalise(name) : null;
        } catch (Exception e) {
            log.debug("Could not read the language count for interview {}", scheduleId, e);
            return null;
        }
    }

    private void clearLanguageCount(Long scheduleId) {
        if (sharedState == null) {
            return;
        }
        sharedState.delete(AudioTranscriptionServiceKeys.total(scheduleId));
        sharedState.delete(AudioTranscriptionServiceKeys.foreign(scheduleId));
        sharedState.delete(AudioTranscriptionServiceKeys.name(scheduleId));
        sharedState.delete(com.rightpath.service.AudioTranscriptionService.languageWarnedKey(scheduleId));
        // Language checks still running for the answer just sent are not counted against the next one.
        sharedState.increment(com.rightpath.service.AudioTranscriptionService.languageGenerationKey(scheduleId),
                java.time.Duration.ofHours(3));
    }

    /**
     * Marks the last answer as not in English when the interviewer saw it from the text, and tells the candidate.
     * Left alone if the speech check had already marked it, so the candidate is not told twice.
     */
    private void markLastAnswerNotEnglish(Long scheduleId) {
        try {
            VoiceConversationEntry last = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId)
                    .stream()
                    .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (last == null || last.getContent() == null || last.getContent().startsWith(NOT_IN_ENGLISH)) {
                return;
            }
            last.setContent(NOT_IN_ENGLISH + ": the interviewer found this answer was not in English (another "
                    + "language, or one written out in English letters). It earns no marks, including for "
                    + "communication.]\n" + last.getContent());
            entryRepository.save(last);
            messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/language-warning",
                    Map.of("source", "interviewer"));
            log.info("Interview {}: the interviewer found the last answer was not in English", scheduleId);
        } catch (Exception e) {
            log.warn("Could not mark the last answer of interview {} as not in English", scheduleId, e);
        }
    }

    private static String capitalise(String value) {
        return value == null || value.isBlank() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Key names, kept in one place with the service that writes them. */
    private static final class AudioTranscriptionServiceKeys {
        static String total(Long id) {
            return com.rightpath.service.AudioTranscriptionService.languageTotalKey(id);
        }

        static String foreign(Long id) {
            return com.rightpath.service.AudioTranscriptionService.languageForeignKey(id);
        }

        static String name(Long id) {
            return com.rightpath.service.AudioTranscriptionService.languageNameKey(id);
        }
    }

    /** Whether the question this answer replied to asked for code. */
    private boolean answeredCodingQuestion(Long scheduleId, VoiceConversationEntry answer) {
        VoiceConversationEntry question = null;
        for (VoiceConversationEntry entry : entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId)) {
            if (entry.getId() != null && entry.getId().equals(answer.getId())) {
                break;
            }
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                question = entry;
            }
        }
        return question != null && question.getContent() != null
                && CODING_OPENING.matcher(question.getContent()).find();
    }

    /**
     * Whether the candidate has plainly stopped answering correctly: once enough answers are rated, the last few
     * average below the mark. Only rated, non-introduction answers count, so one bad question never ends anything.
     */
    /**
     * Whether every skill the job needs has been asked about at least once (technical round). Weak answers on one skill
     * used to end the whole interview before the later skills were reached, so the report showed HTML and CSS as "not
     * assessed" on a candidate who was never asked about them. The early stop now waits until each skill has had its
     * turn; a weak skill still stops on its own after its misses.
     */
    private boolean everySkillStarted(CandidateInterviewSchedule schedule) {
        if (levelProgression == null
                || schedule.getEffectiveRound() != com.rightpath.enums.InterviewRound.L2_TECHNICAL) {
            return true;
        }
        java.util.List<String> required = contextService.requiredSkills(schedule);
        if (required == null || required.isEmpty()) {
            return true;
        }
        return levelProgression.notStarted(
                entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId()), required).isEmpty();
    }

    boolean shouldStopEarly(Long scheduleId) {
        if (!earlyStopEnabled) {
            return false;
        }
        List<Integer> scores = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId).stream()
                .filter(e -> e.getRole() == ConversationRole.CANDIDATE && e.getAnswerScore() != null)
                .map(VoiceConversationEntry::getAnswerScore)
                .skip(1) // the introduction
                .toList();
        if (scores.size() < earlyStopMinAnswers) {
            return false;
        }
        List<Integer> recent = scores.subList(scores.size() - Math.min(earlyStopWindow, scores.size()), scores.size());
        double average = recent.stream().mapToInt(Integer::intValue).average().orElse(10);
        return average < earlyStopMaxAverage;
    }

    /**
     * Notes on the proctoring record that the interview was opened in another window while it was running, so a
     * reviewer can see it. Best effort: failing to record it must not stop the candidate opening the interview.
     */
    private void recordSessionTakeover(CandidateInterviewSchedule schedule) {
        if (proctoringEventRepository == null) {
            return;
        }
        try {
            proctoringEventRepository.save(com.rightpath.entity.ProctoringEvent.builder()
                    .schedule(schedule)
                    .eventType("interview_opened_elsewhere")
                    .details("The interview was opened in another browser window while it was running. The earlier "
                            + "window was closed and the interview continued in the new one.")
                    .build());
        } catch (Exception e) {
            log.warn("Could not record the second window for interview {}", schedule.getId(), e);
        }
    }

    /**
     * Puts the rating the model just gave onto the answer it was rating.
     *
     * <p>Best effort. A rating that cannot be attached is a slightly worse
     * difficulty signal on the next turn; failing the turn over it would cost
     * the candidate an answer.</p>
     */
    private boolean applyScoreToLastAnswer(Long scheduleId, Integer score, boolean codeMismatch) {
        try {
            VoiceConversationEntry last = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId)
                    .stream()
                    .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (last == null) {
                return false;
            }
            // Code that solves a different problem than the one asked earns nothing, whatever rating came with it.
            // Only an answer that carried code can be one: the flag is ignored on a spoken answer.
            boolean hasCode = last.getCodeContent() != null && !last.getCodeContent().isBlank();
            boolean mismatch = codeMismatch && hasCode;
            Integer applied = mismatch ? Integer.valueOf(0) : score;
            // An answer not spoken in English earns nothing: what was rated was a machine translation.
            if (applied != null && last.getContent() != null && last.getContent().startsWith(NOT_IN_ENGLISH)) {
                applied = 0;
            }
            // A coding question answered only by talking has not shown coding: the approach can earn a little,
            // never a coding score. It was being rated 7/10 for a spoken outline.
            if (applied != null && !hasCode && answeredCodingQuestion(scheduleId, last)) {
                applied = Math.min(applied, spokenOnlyCodingMax);
            }
            if (applied != null) {
                last.setAnswerScore(applied);
                if (levelProgression != null) {
                    last.setAnswerCorrect(levelProgression.isCorrect(applied));
                }
            }
            if (mismatch) {
                last.setCodeMismatch(true);
                log.info("Interview {}: submitted code solves a different problem; scored 0", scheduleId);
            }
            entryRepository.save(last);
            return mismatch;
        } catch (Exception e) {
            log.warn("Could not attach the answer score for interview {}", scheduleId, e);
            return false;
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
    private static final String CLOSING_QUESTION_DIRECTIVE =
            "This is the last turn before the interview closes. Do NOT ask another interview question. Briefly "
                    + "acknowledge the answer, then ask only the closing question: whether the candidate has any "
                    + "questions for us about the role or the process. Do not end the interview in this reply and "
                    + "do not include " + "[INTERVIEW_COMPLETE].";

    /** How many questions the candidate may put to us once the closing question has been asked. */
    private static final int MAX_CLOSING_QUESTIONS = 3;

    private static final String CLOSING_ANSWER_DIRECTIVE =
            "The candidate has asked a question in reply to your closing question. Answer it in a sentence or two "
                    + "in general terms (no salary, offers or how the interview went). Then ask whether they have any "
                    + "other questions for us. Do NOT ask an interview question, do NOT start a new topic, do not "
                    + "end the interview in this reply and do not include [INTERVIEW_COMPLETE].";

    /** Said when the candidate answers "yes" to the closing question without asking yet. */
    static final String INVITE_QUESTION = "Of course, please go ahead. Anything you would like to ask about the role "
            + "or the process, I'm happy to answer.";

    /**
     * Whether a reply to "do you have any questions?" means "yes, I do" without the question itself: a short yes,
     * not a no, and not already a question (that is answered directly).
     */
    static boolean saysTheyHaveAQuestion(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return false;
        }
        String text = transcript.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || text.split(" ").length > 15
                || text.matches("^(no|nope|nothing|not really|none|i don'?t|i do not|that'?s all|no thanks).*")) {
            return false;
        }
        return text.matches("^(yes|yeah|yep|yup|ya|yah|sure|ok yes|okay yes|haan|ha|i do|i have|i've got|i got|one "
                + "question|a question|a few questions|few questions|actually yes|yes i do|yes i have).*");
    }

    private static final java.util.Set<String> ENGLISH_WORDS = java.util.Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "to", "of", "and", "in", "on", "for", "with", "it",
            "that", "this", "as", "by", "we", "you", "i", "my", "our", "can", "used", "use", "which", "from", "or",
            "have", "has", "will", "would", "what", "how", "when", "where", "so", "but", "not", "do", "does", "they",
            "there", "then", "if", "at", "into", "about", "like", "also", "its", "their", "easily");

    /**
     * Whether an answer is plainly English: enough of its words are common English words. Another language written
     * out in English letters ("nenu project lo work chesanu") has almost none, so it is still caught.
     */
    static boolean looksEnglish(String transcript) {
        if (transcript == null) {
            return false;
        }
        String[] words = transcript.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ").trim()
                .split("\\s+");
        if (words.length < 4) {
            return false;
        }
        long common = java.util.Arrays.stream(words).filter(ENGLISH_WORDS::contains).count();
        return common * 4 >= words.length;
    }

    private static final String FINAL_REPLY_DIRECTIVE =
            "The candidate has replied to your closing question, so this is your FINAL reply. If they asked "
                    + "something, answer it in a sentence or two in general terms (no salary, offers or how the "
                    + "interview went). Then thank them, say the team will be in touch and wish them well. Do NOT "
                    + "ask any question and do NOT start a new topic, including teamwork or behavioural questions. "
                    + "End with [INTERVIEW_COMPLETE].";

    private static final String FALLBACK_FAREWELL =
            "Thank you for your time today. The team will be in touch. Take care.";

    /**
     * Whether the interviewer's last turn was the closing question, so the candidate's answer in hand is to it.
     * The last interviewer entry is the one the candidate has just answered.
     */
    private boolean closingQuestionAlreadyAsked(Long scheduleId) {
        java.util.List<VoiceConversationEntry> entries =
                entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            VoiceConversationEntry entry = entries.get(i);
            if (entry.getRole() != ConversationRole.INTERVIEWER) {
                continue;
            }
            return isClosingQuestion(entry.getContent());
        }
        return false;
    }

    /**
     * The level to record for a turn, or null when the turn is not an interview question. The closing question
     * ("any questions for us?") and the goodbye were saved with whatever level the model put on them, so the
     * transcript labelled "do you have any questions for us" as Expert.
     */
    private static String levelOf(ParsedInterviewReply reply) {
        if (reply == null || reply.closing() || isClosingQuestion(reply.spokenText())) {
            return null;
        }
        return reply.level();
    }

    /** The closing question in any of its wordings, including "any other questions for us?" after an answer. */
    private static boolean isClosingQuestion(String content) {
        String text = content == null ? "" : content.toLowerCase();
        return text.contains("any questions for us") || text.contains("any questions about the role")
                || text.contains("any other questions") || text.contains("any more questions")
                // Other wordings the model reaches for. Left out, a closing question the matcher did not recognise
                // read as "not asked", so the next reply was treated as an ordinary answer and the interview was
                // closed after a single feedback question instead of letting the candidate ask their questions.
                || text.contains("questions for me") || text.contains("any questions on the role")
                || text.contains("anything you would like to ask") || text.contains("anything you'd like to ask")
                || text.contains("anything else you would like to know") || text.contains("anything else you'd like to know")
                || text.contains("any feedback or questions") || text.contains("any questions or feedback")
                || text.contains("any questions you would like to ask") || text.contains("any questions you'd like to ask");
    }

    /** How many times the candidate has replied since the closing question was first asked, the current reply included. */
    private int candidateRepliesToClosing(Long scheduleId) {
        java.util.List<VoiceConversationEntry> entries =
                entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId);
        int firstClosing = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getRole() == ConversationRole.INTERVIEWER && isClosingQuestion(entries.get(i).getContent())) {
                firstClosing = i;
                break;
            }
        }
        if (firstClosing < 0) {
            return 0;
        }
        int replies = 0;
        for (int i = firstClosing + 1; i < entries.size(); i++) {
            if (entries.get(i).getRole() == ConversationRole.CANDIDATE) {
                replies++;
            }
        }
        return replies;
    }

    /**
     * Whether what the candidate said is a question, as opposed to "no, that's all". Judged on a question mark or an
     * opening question word, because the transcript is plain text with no other cue.
     */
    private static boolean looksLikeAQuestion(String transcript) {
        if (transcript == null) {
            return false;
        }
        String text = transcript.trim().toLowerCase();
        if (text.isEmpty()) {
            return false;
        }
        if (text.contains("?")) {
            return true;
        }
        return text.matches("^(what|when|where|which|who|why|how|can|could|will|would|is|are|do|does|did|may)\\b.*");
    }

    private static final java.util.regex.Pattern CODING_OPENING =
            java.util.regex.Pattern.compile("^\\s*\\[\\s*CODING\\s*\\]", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether the reply asks the candidate to write code (it opens with the coding tag). */
    private static boolean isCodingQuestion(ParsedInterviewReply reply) {
        return reply != null && !reply.closing() && reply.spokenText() != null
                && CODING_OPENING.matcher(reply.spokenText()).find();
    }

    /** Whether a coding question shows at least one example, or states an input and an output. */
    static boolean hasExamples(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return lower.contains("example") || lower.contains("sample")
                || (lower.contains("input") && lower.contains("output"));
    }

    /**
     * Asks once for the same coding question again, with two examples. If the second try is no better the original
     * stands: a coding question without examples is better than a stalled interview.
     */
    private ParsedInterviewReply askForExamples(CandidateInterviewSchedule schedule, ParsedInterviewReply original,
            String turnPrompt) {
        try {
            String reply = openAiStreamingService.chatCompletion(contextService.buildContextMessages(schedule,
                    turnPrompt + "\n\n" + CODING_EXAMPLES_DIRECTIVE));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply retry = replyParser.parse(reply);
                if (isCodingQuestion(retry) && hasExamples(retry.spokenText())) {
                    Integer score = retry.answerScore() != null ? retry.answerScore() : original.answerScore();
                    String topic = retry.topic() != null ? retry.topic() : original.topic();
                    return new ParsedInterviewReply(retry.spokenText(), score, retry.turnKind(), topic, false,
                            retry.level() != null ? retry.level() : original.level(),
                            original.codeMismatch() || retry.codeMismatch(),
                            retry.skill() != null ? retry.skill() : original.skill(),
                            original.notEnglish() || retry.notEnglish(),
                            retry.competency() != null ? retry.competency() : original.competency());
                }
            }
        } catch (Exception e) {
            log.warn("Could not get examples for the coding question of interview {}", schedule.getId(), e);
        }
        return original;
    }

    /**
     * The conversation so far with the rating this reply gives the last answer put on it, so the plan counts that
     * answer: its score arrives in the same reply as the next question, and without it the plan lagged an answer.
     */
    private java.util.List<VoiceConversationEntry> historyWithScore(Long scheduleId, Integer score) {
        java.util.List<VoiceConversationEntry> history =
                new java.util.ArrayList<>(entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId));
        if (score == null) {
            return history;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            VoiceConversationEntry entry = history.get(i);
            if (entry.getRole() == ConversationRole.CANDIDATE) {
                if (entry.getAnswerScore() == null) {
                    history.set(i, VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE)
                            .content(entry.getContent()).codeContent(entry.getCodeContent()).answerScore(score)
                            .build());
                }
                break;
            }
        }
        return history;
    }

    /** What is wrong with a question against the plan, in words for the interviewer; null when it matches. */
    static String planMismatch(com.rightpath.service.InterviewLevelProgression.Expected expected,
            ParsedInterviewReply reply, java.util.List<String> required) {
        if (expected == null) {
            return null;
        }
        java.util.List<String> problems = new java.util.ArrayList<>();
        String skill = com.rightpath.service.InterviewLevelProgression.canonical(reply.skill(), required);
        // The tag can say one skill while the question moves to another: "Let's shift our focus to machine learning
        // now" went out tagged Python, so it passed as Python, a skip on it counted against Python, and the next
        // question went "back" to Python. What the question says it moves to is checked as well as its tag.
        String announced = announcedSkillSwitch(reply.spokenText(), required);
        if (announced != null && !announced.equals(expected.skill())) {
            problems.add("it moves on to " + announced + ", but the skill in progress is " + expected.skill()
                    + (announced.equals(skill) ? "" : ", and its [SKILL] tag does not say " + announced));
        } else if (!expected.skill().equals(skill)) {
            problems.add("it is on " + (reply.skill() == null ? "no named skill" : reply.skill())
                    + ", but the skill in progress is " + expected.skill());
        }
        if (!expected.level().equals(reply.level())) {
            problems.add("it is at " + (reply.level() == null ? "no level" : reply.level())
                    + ", but " + expected.skill() + " is to be asked at " + expected.level());
        }
        if (expected.coding() && !isCodingQuestion(reply)) {
            problems.add(expected.skill() + " owes its coding task at " + expected.level() + ", and this is not one");
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** "Let's shift our focus to …", "moving on to …", "now let's talk about …": the words that start a new skill. */
    private static final String SWITCH_WORDS = "(?:shift|shifting|switch|switching|move|moving|turn|turning|go|going"
            + "|transition|transitioning|change|changing|return|returning|back)\\b[^.?!\\n]{0,40}?\\b(?:to|onto|towards)\\b"
            + "|\\blet'?s (?:talk|discuss|chat)(?: a bit| now)? about\\b|\\bnow,? (?:on|for|about)\\b";

    /**
     * The required skill a question says it is moving on to, by its own words, or null when it announces no switch.
     * Only an announced switch counts: a Python question that happens to mention machine learning is still Python.
     */
    static String announcedSkillSwitch(String text, java.util.List<String> required) {
        if (text == null || text.isBlank() || required == null || required.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String found = null;
        int foundAt = Integer.MAX_VALUE;
        for (String skill : required) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            String name = java.util.regex.Pattern.quote(skill.trim().toLowerCase(java.util.Locale.ROOT));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:" + SWITCH_WORDS + ")[^.?!\\n]{0,25}?(?<![a-z0-9])" + name + "(?![a-z0-9])")
                    .matcher(lower);
            // The first switch the question announces is the one it makes.
            if (m.find() && m.start() < foundAt) {
                foundAt = m.start();
                found = skill.trim();
            }
        }
        return found;
    }

    /** Asks once for the question the plan calls for; the original stands if the second try does not match either. */
    private ParsedInterviewReply askToFollowPlan(CandidateInterviewSchedule schedule, ParsedInterviewReply original,
            String turnPrompt, String problem,
            com.rightpath.service.InterviewLevelProgression.Expected expected, java.util.List<String> required) {
        try {
            log.info("Interview {}: question sent back, {}", schedule.getId(), problem);
            String directive = "Your question does not follow the assessment plan: " + problem + ". Keep your [SCORE] "
                    + "for the last answer, acknowledge it briefly, and ask your next question on " + expected.skill()
                    + " at " + expected.level()
                    + (expected.coding() ? ", as a coding task ([CODING] with a Task type line and two examples)" : "")
                    + ", tagged [SKILL:" + expected.skill() + "] [LEVEL:" + expected.level() + "] with its [TOPIC] and "
                    + "[COMPETENCY].";
            String reply = openAiStreamingService.chatCompletion(
                    contextService.buildContextMessages(schedule, turnPrompt + "\n\n" + directive));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply retry = replyParser.parse(reply);
                if (!retry.closing() && planMismatch(expected, retry, required) == null) {
                    Integer score = retry.answerScore() != null ? retry.answerScore() : original.answerScore();
                    return new ParsedInterviewReply(retry.spokenText(), score, TurnKind.NEW_QUESTION,
                            retry.topic() != null ? retry.topic() : original.topic(), false, retry.level(),
                            original.codeMismatch() || retry.codeMismatch(), retry.skill(),
                            original.notEnglish() || retry.notEnglish(), retry.competency());
                }
            }
        } catch (Exception e) {
            log.warn("Could not bring interview {} back to its plan", schedule.getId(), e);
        }
        log.warn("Interview {}: the question still did not follow the plan after one retry", schedule.getId());
        // It goes out as it is, but filed under the skill it really asks about, so the answer (or a skip) counts
        // there and the next question does not swing back to the skill the tag wrongly named.
        String announced = announcedSkillSwitch(original.spokenText(), required);
        if (announced != null && !announced.equals(
                com.rightpath.service.InterviewLevelProgression.canonical(original.skill(), required))) {
            return new ParsedInterviewReply(original.spokenText(), original.answerScore(), original.turnKind(),
                    original.topic(), original.closing(), original.level(), original.codeMismatch(), announced,
                    original.notEnglish(), original.competency());
        }
        return original;
    }

    /** Said first, every time an answer was not in English, before the interviewer re-asks the question. */
    static final String ENGLISH_NOTICE = "You are speaking in another language, but this interview can only be done "
            + "in English, so this answer won't be evaluated. Please answer in English.";

    /** The reply with the English notice in front (after the coding tag, if any), unless it already says so. */
    static ParsedInterviewReply withEnglishNotice(ParsedInterviewReply reply) {
        String text = reply.spokenText() == null ? "" : reply.spokenText();
        if (text.toLowerCase(java.util.Locale.ROOT).contains("won't be evaluated")) {
            return reply;
        }
        java.util.regex.Matcher coding = CODING_OPENING.matcher(text);
        String noticed = coding.find()
                ? text.substring(0, coding.end()) + " " + ENGLISH_NOTICE + " " + text.substring(coding.end()).trim()
                : ENGLISH_NOTICE + " " + text;
        return new ParsedInterviewReply(noticed.trim(), reply.answerScore(), reply.turnKind(), reply.topic(),
                reply.closing(), reply.level(), reply.codeMismatch(), reply.skill(), true, reply.competency());
    }

    /** Whether the answer just saved was marked by the speech check as not in English. */
    private boolean lastAnswerMarkedNotEnglish(Long scheduleId) {
        return entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId).stream()
                .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                .reduce((first, second) -> second)
                .map(e -> e.getContent() != null && e.getContent().startsWith(NOT_IN_ENGLISH))
                .orElse(false);
    }

    /** Wordings that ask the candidate to fix the code they have just written. */
    private static final java.util.regex.Pattern CORRECT_CODE = java.util.regex.Pattern.compile(
            "\\b(fix|correct|update|modify|change|rewrite|revise|improve|debug|adjust)\\b[^.?!\\n]{0,60}"
                    + "\\b(code|function|solution|implementation|logic|program|loop|method|query)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether a reply asks the candidate to fix their code ("could you correct your function?"). */
    static boolean asksToCorrectCode(String text) {
        return text != null && CORRECT_CODE.matcher(text).find();
    }

    /** Whether the candidate's latest answer included code. */
    private boolean lastAnswerHadCode(Long scheduleId) {
        return entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId).stream()
                .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                .reduce((first, second) -> second)
                .map(e -> e.getCodeContent() != null && !e.getCodeContent().isBlank())
                .orElse(false);
    }

    /** Whether the interviewer's latest question was itself a correction of a coding task. */
    private static boolean lastQuestionWasCorrection(List<VoiceConversationEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            VoiceConversationEntry entry = entries.get(i);
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                return entry.getContent() != null && CODING_OPENING.matcher(entry.getContent()).find()
                        && entry.getTurnKind() != null && entry.getTurnKind() != TurnKind.NEW_QUESTION;
            }
        }
        return false;
    }

    /**
     * Whether this coding question is one already asked in this interview. A re-ask is allowed only when the
     * candidate has not yet sent any code for it (they could not see the notepad), so it is not a repeat.
     */
    private boolean repeatsEarlierCodingProblem(Long scheduleId, ParsedInterviewReply reply) {
        List<VoiceConversationEntry> entries = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId);
        java.util.Set<String> asked = problemWords(reply.spokenText());
        if (asked.size() < 4) {
            return false;
        }
        boolean answeredWithCode = entries.stream()
                .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                .reduce((first, second) -> second)
                .map(e -> e.getCodeContent() != null && !e.getCodeContent().isBlank())
                .orElse(false);
        if (reply.turnKind() == TurnKind.REPHRASE && !answeredWithCode) {
            return false;
        }
        // One correction of the code just submitted is allowed; a second one on the same task is a repeat.
        if (reply.turnKind() != TurnKind.NEW_QUESTION && answeredWithCode && !lastQuestionWasCorrection(entries)) {
            return false;
        }
        return entries.stream()
                .filter(e -> e.getRole() == ConversationRole.INTERVIEWER && e.getContent() != null
                        && CODING_OPENING.matcher(e.getContent()).find())
                .anyMatch(e -> similarity(asked, problemWords(e.getContent())) >= 0.8);
    }

    /** The words of a coding problem's statement: no tag, task type or examples, so wording around it is ignored. */
    static java.util.Set<String> problemWords(String text) {
        if (text == null) {
            return java.util.Set.of();
        }
        String statement = text.replaceAll("(?i)\\[\\s*CODING\\s*\\]", " ")
                .replaceAll("(?im)^\\s*task type:.*$", " ")
                .replaceAll("(?is)example\\s*1.*", " ")
                .toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String word : statement.split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                words.add(word);
            }
        }
        return words;
    }

    /** Share of words two problem statements have in common (Jaccard), 0 to 1. */
    static double similarity(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        java.util.Set<String> common = new java.util.HashSet<>(a);
        common.retainAll(b);
        java.util.Set<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return (double) common.size() / all.size();
    }

    /**
     * Asks once for a different question in place of a repeated coding problem. If the second try still repeats it,
     * the original stands rather than stalling the interview; it is logged so it can be seen.
     */
    private ParsedInterviewReply askForDifferentProblem(CandidateInterviewSchedule schedule,
            ParsedInterviewReply original, String turnPrompt) {
        try {
            String reply = openAiStreamingService.chatCompletion(contextService.buildContextMessages(schedule,
                    turnPrompt + "\n\n" + NEW_PROBLEM_DIRECTIVE));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply retry = replyParser.parse(reply);
                if (!isCodingQuestion(retry)
                        || (hasExamples(retry.spokenText()) && !repeatsEarlierCodingProblem(schedule.getId(), retry))) {
                    Integer score = retry.answerScore() != null ? retry.answerScore() : original.answerScore();
                    return new ParsedInterviewReply(retry.spokenText(), score, TurnKind.NEW_QUESTION,
                            retry.topic() != null ? retry.topic() : original.topic(), retry.closing(),
                            retry.level() != null ? retry.level() : original.level(),
                            original.codeMismatch() || retry.codeMismatch(),
                            retry.skill() != null ? retry.skill() : original.skill(),
                            original.notEnglish() || retry.notEnglish(),
                            retry.competency() != null ? retry.competency() : original.competency());
                }
            }
        } catch (Exception e) {
            log.warn("Could not replace a repeated coding problem in interview {}", schedule.getId(), e);
        }
        log.warn("Interview {} asked a coding problem again after it was answered", schedule.getId());
        return original;
    }

    /** Whether this question's competency was already tested for the same skill in this interview. */
    private boolean repeatsCompetency(CandidateInterviewSchedule schedule, ParsedInterviewReply reply) {
        if (reply.competency() == null || reply.skill() == null) {
            return false;
        }
        java.util.List<String> required = contextService.requiredSkills(schedule);
        String skill = contextService.canonicalSkill(schedule, reply.skill());
        String wanted = reply.competency().toLowerCase().replaceAll("[^a-z0-9+#]", "");
        if (wanted.isEmpty()) {
            return false;
        }
        return com.rightpath.service.InterviewLevelProgression.competenciesTested(
                        entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId()),
                        required.isEmpty() ? java.util.List.of(skill) : required, skill)
                .stream()
                .anyMatch(c -> c.toLowerCase().replaceAll("[^a-z0-9+#]", "").equals(wanted));
    }

    /** Asks once for a question not asked before; the original stands if the second try repeats too. */
    private ParsedInterviewReply askForUnaskedQuestion(CandidateInterviewSchedule schedule,
            ParsedInterviewReply original, String turnPrompt, String earlier) {
        try {
            log.info("Interview {}: question sent back, it repeats an earlier one", schedule.getId());
            String reply = openAiStreamingService.chatCompletion(contextService.buildContextMessages(schedule,
                    turnPrompt + "\n\nYour question repeats one already asked in this interview: \"" + earlier
                            + "\". Keep your [SCORE] for the last answer and ask a question that tests something no "
                            + "earlier question did, on the same skill and at the same level, with all its tags. Do "
                            + "not reword the earlier question."));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply retry = replyParser.parse(reply);
                if (!retry.closing() && InterviewContextService.repeatedQuestion(
                        entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId()),
                        retry.spokenText()) == null) {
                    Integer score = retry.answerScore() != null ? retry.answerScore() : original.answerScore();
                    return new ParsedInterviewReply(retry.spokenText(), score, TurnKind.NEW_QUESTION,
                            retry.topic() != null ? retry.topic() : original.topic(), false,
                            retry.level() != null ? retry.level() : original.level(),
                            original.codeMismatch() || retry.codeMismatch(),
                            retry.skill() != null ? retry.skill() : original.skill(),
                            original.notEnglish() || retry.notEnglish(), retry.competency());
                }
            }
        } catch (Exception e) {
            log.warn("Could not replace a repeated question in interview {}", schedule.getId(), e);
        }
        return original;
    }

    /** Asks once for a question on a different competency; the original stands if the second try repeats too. */
    private ParsedInterviewReply askForDifferentCompetency(CandidateInterviewSchedule schedule,
            ParsedInterviewReply original, String turnPrompt) {
        try {
            String reply = openAiStreamingService.chatCompletion(contextService.buildContextMessages(schedule,
                    turnPrompt + "\n\nYour question tests \"" + original.competency() + "\", which was already tested "
                            + "for " + original.skill() + ". Keep your [SCORE] for the last answer and ask a question "
                            + "on a DIFFERENT competency of " + original.skill() + " at the same level, with all its "
                            + "tags. Do not reword the earlier question."));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply retry = replyParser.parse(reply);
                if (!retry.closing() && !repeatsCompetency(schedule, retry)) {
                    Integer score = retry.answerScore() != null ? retry.answerScore() : original.answerScore();
                    return new ParsedInterviewReply(retry.spokenText(), score, TurnKind.NEW_QUESTION,
                            retry.topic() != null ? retry.topic() : original.topic(), false,
                            retry.level() != null ? retry.level() : original.level(),
                            original.codeMismatch() || retry.codeMismatch(),
                            retry.skill() != null ? retry.skill() : original.skill(),
                            original.notEnglish() || retry.notEnglish(), retry.competency());
                }
            }
        } catch (Exception e) {
            log.warn("Could not replace a repeated competency in interview {}", schedule.getId(), e);
        }
        return original;
    }

    private static final String NEW_PROBLEM_DIRECTIVE =
            "Your reply asked a coding problem that was already asked in this interview, and the candidate's answer "
                    + "to it has been recorded and rated. Do NOT ask it again or reword it. Keep your [SCORE] for "
                    + "their last answer, say in at most one sentence what was right or missing in it (never the "
                    + "solution), then ask a DIFFERENT question: a new coding problem on different ground, with its "
                    + "Task type line and exactly two examples, or a question on another skill.";

    private static final String CODING_EXAMPLES_DIRECTIVE =
            "Your reply asked a coding question without examples. Send the SAME coding question again, starting "
                    + "with [CODING], and after the problem statement add exactly two examples, each written as "
                    + "\"Example 1: Input: ... Output: ...\" and \"Example 2: Input: ... Output: ...\". Do not change "
                    + "the problem, do not add a solution and do not ask anything else.";

    /**
     * A goodbye to end on, when the reply in hand is not one. Asks the model once; if that is no better, a fixed
     * thank-you is used, because an interview that ends must end on a thank-you and not on a question.
     * The original reply's score and topic are kept so the answer is still rated.
     */
    private ParsedInterviewReply askForFarewell(CandidateInterviewSchedule schedule, ParsedInterviewReply original) {
        String text = FALLBACK_FAREWELL;
        Integer score = original.answerScore();
        try {
            String reply = openAiStreamingService.chatCompletion(
                    contextService.buildContextMessages(schedule, FINAL_REPLY_DIRECTIVE));
            if (reply != null && !reply.isBlank()) {
                ParsedInterviewReply parsed = replyParser.parse(reply);
                if (parsed.closing() && !parsed.spokenText().isBlank() && !parsed.spokenText().contains("?")) {
                    text = parsed.spokenText();
                    if (score == null) {
                        score = parsed.answerScore();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not get a farewell for interview {}", schedule.getId(), e);
        }
        return new ParsedInterviewReply(text, score, original.turnKind(), original.topic(), true, original.level(),
                original.codeMismatch(), original.skill(), original.notEnglish(), original.competency());
    }

    /**
     * Asks once for the next question instead of a close, saying what the assessment still lacks. Null when the model
     * still closes: it is not asked again, so this can never loop.
     */
    private ParsedInterviewReply askToContinue(CandidateInterviewSchedule schedule, String gap) {
        try {
            log.info("Holding the close on interview {}: {}", schedule.getId(), gap);
            String reply = openAiStreamingService.chatCompletion(contextService.buildContextMessages(schedule, gap));
            if (reply == null || reply.isBlank()) {
                return null;
            }
            ParsedInterviewReply parsed = replyParser.parse(reply);
            return parsed.closing() || isClosingQuestion(parsed.spokenText()) ? null : parsed;
        } catch (Exception e) {
            log.warn("Could not continue interview {} past an early close", schedule.getId(), e);
            return null;
        }
    }

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

    /**
     * Moves the candidate's application on once an interview has finished, so the pipeline stage follows what has
     * happened instead of staying at "Interview Scheduled" for good. Only an application still at that stage moves:
     * a later round, or a candidate already selected or rejected, keeps the stage they are at.
     */
    private void advanceApplicationAfterInterview(CandidateInterviewSchedule schedule) {
        jobAppRepo.findByJobPost_JobPrefixAndUser_Email(schedule.getJobPrefix(), schedule.getEmail())
                .ifPresent(application -> {
                    if (application.getStatus() == com.rightpath.enums.ApplicationStatus.INTERVIEW_SCHEDULED) {
                        application.setStatus(com.rightpath.enums.ApplicationStatus.INTERVIEW_COMPLETED);
                        application.setInterview("Completed");
                        jobAppRepo.save(application);
                        log.info("Application of {} on {} moved to INTERVIEW_COMPLETED", schedule.getEmail(),
                                schedule.getJobPrefix());
                    }
                });
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
        schedule.setEndedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        schedule.setCompletionReason(reason);
        scheduleRepo.save(schedule);
        advanceApplicationAfterInterview(schedule);

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
            schedule.setEndedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
            schedule.setCompletionReason(CompletionReason.PROCTORING_VIOLATION);
            scheduleRepo.save(schedule);
            advanceApplicationAfterInterview(schedule);
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
    @SchedulerLock(name = "enforceInterviewTimeouts", lockAtLeastFor = "PT2M", lockAtMostFor = "PT10M")
    @Transactional
    public void enforceInterviewTimeouts() {
        // With a time limit set, that is the cutoff. With none, only an interview open for the abandonment period is
        // closed, and that is a clean-up of sessions nobody is in, not a clock on a real interview.
        LocalDateTime cutoff = maxDurationMinutes > 0
                ? LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(maxDurationMinutes)
                : LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(abandonedAfterHours);
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
            advanceApplicationAfterInterview(schedule);

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

    private void sendFiller(Long scheduleId, String filler) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("text", filler);
        msg.put("type", "filler");
        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/filler", msg);
    }
    
 // Send response using a String (fetches schedule from DB)
    private void sendResponseComplete(Long scheduleId, String response, boolean isComplete, String endReason) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
        sendResponseComplete(scheduleId, schedule, response, isComplete, endReason);
    }

    /**
     * As above, and says whether the turn is a new question, a follow-up or a rephrase. The browser uses it to put the
     * candidate's last code back in the notepad when they are asked to fix it, instead of a blank page.
     */
    private void sendResponseComplete(Long scheduleId, String response, boolean isComplete, String endReason,
            com.rightpath.enums.TurnKind turnKind, boolean codeMismatch, boolean notEnglish) {
        CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
        Map<String, Object> message = new HashMap<>();
        if (endReason != null && isComplete) {
            message.put("reason", endReason);
        }
        message.put("response", response);
        message.put("questionsAsked", schedule.getTotalQuestionsAsked());
        message.put("isComplete", isComplete);
        message.put("terminated", false);
        if (turnKind != null) {
            message.put("turnKind", turnKind.name());
        }
        if (notEnglish) {
            // Carried on the reply itself, so the "please answer in English" alert shows with the interviewer's words
            // and cannot be missed the way a separate message could be.
            message.put("notEnglish", true);
        }
        if (codeMismatch) {
            // The candidate is told their last code did not answer the question, so they are not left thinking it
            // counted.
            message.put("codeMismatch", true);
        }
        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/response-complete", message);
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
