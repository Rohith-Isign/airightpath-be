package com.rightpath.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.rightpath.dto.voice.VoiceAnswerRequest;

import lombok.AllArgsConstructor;
import lombok.Data;

@Service
public class ToneAnalysisService {

    private static final Set<String> FILLER_WORDS = Set.of(
            "um", "uh", "like", "you know", "basically", "actually",
            "literally", "right", "so", "well", "i mean", "kind of",
            "sort of", "you see", "okay so", "yeah"
    );

    /**
     * Analyze tone metrics from transcription data.
     */
    public ToneMetrics analyze(String transcript, List<VoiceAnswerRequest.WordTimestamp> wordTimestamps, double speechDuration) {
        if (transcript == null || transcript.isBlank()) {
            return ToneMetrics.empty();
        }

        String[] words = transcript.split("\\s+");
        int wordCount = words.length;

        // Words per minute
        double wpm = speechDuration > 0 ? (wordCount / speechDuration) * 60 : 0;

        // Filler word count
        String lowerTranscript = transcript.toLowerCase();
        int fillerCount = 0;
        for (String filler : FILLER_WORDS) {
            int index = 0;
            while ((index = lowerTranscript.indexOf(filler, index)) != -1) {
                fillerCount++;
                index += filler.length();
            }
        }

        // Pause analysis from word timestamps
        double avgPauseDuration = 0;
        int longPauses = 0;
        if (wordTimestamps != null && wordTimestamps.size() > 1) {
            double totalPause = 0;
            int pauseCount = 0;
            for (int i = 1; i < wordTimestamps.size(); i++) {
                double gap = wordTimestamps.get(i).getStart() - wordTimestamps.get(i - 1).getEnd();
                if (gap > 0.1) {
                    totalPause += gap;
                    pauseCount++;
                    if (gap > 2.0) {
                        longPauses++;
                    }
                }
            }
            avgPauseDuration = pauseCount > 0 ? totalPause / pauseCount : 0;
        }

        // Confidence score (0-100, heuristic)
        double fillerRatio = wordCount > 0 ? (double) fillerCount / wordCount : 0;
        double paceScore = calculatePaceScore(wpm);
        double fillerScore = Math.max(0, 100 - (fillerRatio * 500));
        double pauseScore = Math.max(0, 100 - (longPauses * 15));
        double lengthScore = Math.min(100, wordCount * 2);

        double confidenceScore = (paceScore * 0.3 + fillerScore * 0.3 + pauseScore * 0.2 + lengthScore * 0.2);

        return new ToneMetrics(wordCount, wpm, fillerCount, confidenceScore, speechDuration, avgPauseDuration, longPauses);
    }

    private double calculatePaceScore(double wpm) {
        if (wpm >= 120 && wpm <= 160) return 100;
        if (wpm >= 100 && wpm < 120) return 80;
        if (wpm > 160 && wpm <= 180) return 80;
        if (wpm >= 80 && wpm < 100) return 60;
        if (wpm > 180 && wpm <= 200) return 60;
        return 40;
    }

    @Data
    @AllArgsConstructor
    public static class ToneMetrics {
        private int wordCount;
        private double wordsPerMinute;
        private int fillerWordCount;
        private double confidenceScore;
        private double speechDurationSeconds;
        private double avgPauseDuration;
        private int longPauses;

        public static ToneMetrics empty() {
            return new ToneMetrics(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
