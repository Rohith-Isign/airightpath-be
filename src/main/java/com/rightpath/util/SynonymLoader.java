// File: com/rightpath/util/SynonymLoader.java

package com.rightpath.util;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * SynonymLoader is a Spring component responsible for loading a synonym mapping
 * from a JSON file at application startup.
 *
 * The JSON file should be located in the classpath and follow the structure:
 * {
 *   "term1": ["synonym1", "synonym2"],
 *   "term2": ["synonym3"]
 * }
 */
@Component
@lombok.extern.slf4j.Slf4j
public class SynonymLoader {

    private Map<String, List<String>> synonymMap = new HashMap<>();

    /**
     * Loads the synonyms from the "synonyms.json" file during application startup.
     * This method runs automatically after the bean is initialized due to @PostConstruct.
     */
    @PostConstruct
    public void loadSynonyms() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            InputStream is = getClass().getClassLoader().getResourceAsStream("synonyms.json");
            synonymMap = objectMapper.readValue(is, new TypeReference<Map<String, List<String>>>() {});
            log.info("Synonyms loaded successfully: {} terms", synonymMap.size());
        } catch (Exception e) {
            throw new RuntimeException("❌ Failed to load synonyms file", e);
        }
    }

    /**
     * Returns the loaded synonym map.
     *
     * @return A map where the key is a term and the value is a list of its synonyms.
     */
    public Map<String, List<String>> getSynonymMap() {
        return synonymMap;
    }
}
