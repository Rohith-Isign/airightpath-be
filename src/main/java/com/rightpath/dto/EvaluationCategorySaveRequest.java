package com.rightpath.dto;

import java.util.List;

import com.rightpath.enums.InterviewRound;

import lombok.Data;

@Data
public class EvaluationCategorySaveRequest {

    private String jobPrefix;

    /**
     * Which round these categories score, or null to score both the same way.
     *
     * <p>Null is the shape every job had before rounds could be scored
     * separately, and saving with it still edits that shared list — so an
     * existing console that does not know about rounds keeps working, rather
     * than quietly writing a list that only one round would read.</p>
     */
    private InterviewRound round;

    private List<CategoryItem> categories;

    @Data
    public static class CategoryItem {
        private String categoryName;
        private double weight;
        private String description;
    }
}
