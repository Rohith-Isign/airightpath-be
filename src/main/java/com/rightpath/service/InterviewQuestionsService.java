package com.rightpath.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.InterviewQuestionInfo;


public interface InterviewQuestionsService {

//	  void uploadInterviewQuestions(String jobPrefix, String language, MultipartFile file);
	void uploadInterviewQuestions(String jobPrefix, MultipartFile file);

//	   String fetchInterviewQuestions(String jobPrefix, String language);
	String fetchInterviewQuestions(String jobPrefix);

//	void updateInterviewQuestionsWithAI(String jobPrefix);

	void updateInterviewQuestionsWithAI(String jobPrefix, List<String> categories, int totalQuestions);

	List<InterviewQuestionInfo> loadAndPrepareQuestions(String jobPrefix);

	void updateInterviewQuestionsWithAI(String jobPrefix);

	List<InterviewQuestionInfo> loadAndPrepareQuestions(String jobPrefix, Long fromDate, Long toDate);

}
