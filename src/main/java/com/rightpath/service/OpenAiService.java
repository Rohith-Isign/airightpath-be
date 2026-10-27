package com.rightpath.service;

import java.util.List;

import com.rightpath.dto.CodingQuestion;
import com.rightpath.dto.InterviewQuestion;
import com.rightpath.dto.Question;

public interface OpenAiService {

	List<Question> generateQuestions(String jobPrefix);

	List<CodingQuestion> generateCodingQuestions(String jobPrefix);

	String ask(String conversationHistory);
	
//	List<InterviewQuestion> generateAdditionalQuestions(String jobPrefix, List<InterviewQuestion> existingQuestions, int count);
	
	public List<InterviewQuestion> generateQuestionsForCategories(String jobPrefix, 
            List<String> categories, 
            int totalQuestions);

	String askWithTimeout(String prompt, int timeoutSeconds);

}
