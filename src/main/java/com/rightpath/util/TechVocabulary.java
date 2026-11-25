package com.rightpath.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Technical vocabulary for speech-to-text: the terms to tell the speech model to expect, and the corrections applied to
 * what it writes.
 *
 * <p>The speech model hears ordinary English well and technical terms less well: "Java script", "my sequel",
 * "spring boat", "post gress", "J D B C", "num pie". Two things help. The terms a candidate is likely to say (from the
 * job, the resume and the question in hand) are given to the model as a hint, so it spells them as written; and what
 * it still gets wrong in well-known ways is corrected here afterwards.</p>
 *
 * <p>The corrections are deliberately conservative. They only rewrite forms that are not ordinary English ("hash map"
 * in a Java interview is HashMap; "react" alone is left alone, since "how would it react" is English). A correction
 * that turned a real word into a product name would be worse than the misspelling.</p>
 */
public final class TechVocabulary {

    private TechVocabulary() {
    }

    /** Terms worth hinting, in their written form. Matched against the job, resume and question to pick the hint. */
    static final List<String> GLOSSARY = List.of(
            // Java and its ecosystem
            "Java", "Core Java", "Spring Boot", "Spring MVC", "Spring Security", "Spring Data JPA", "Hibernate", "JPA",
            "JDBC", "Maven", "Gradle", "JUnit", "Mockito", "Lombok", "Tomcat", "JVM", "JDK", "JRE", "HashMap",
            "HashSet", "TreeMap", "ArrayList", "LinkedList", "ConcurrentHashMap", "StringBuilder", "Stream API",
            "lambda expressions", "Optional", "Collections", "multithreading", "ExecutorService", "CompletableFuture",
            "garbage collection", "NullPointerException", "hashCode", "OOP", "OOPs", "polymorphism", "encapsulation",
            "inheritance", "abstraction", "interface", "synchronized", "Java 8",
            // Web and APIs
            "REST API", "RESTful", "microservices", "GraphQL", "JSON", "XML", "YAML", "HTTP", "HTTPS", "OAuth", "JWT",
            "CORS", "WebSocket", "Postman", "Swagger",
            // Front end
            "JavaScript", "TypeScript", "ES6", "React", "ReactJS", "React Hooks", "useState", "useEffect", "useMemo",
            "useCallback", "useContext", "Redux", "JSX", "Virtual DOM", "DOM", "props", "Next.js", "Node.js",
            "Express.js", "Angular", "Vue.js", "jQuery", "HTML", "HTML5", "CSS", "CSS3", "Tailwind CSS", "Bootstrap",
            "Flexbox", "Sass", "Webpack", "Vite", "npm", "AJAX", "Axios", "async/await", "promises",
            // Python and data
            "Python", "Django", "Flask", "FastAPI", "NumPy", "Pandas", "scikit-learn", "TensorFlow", "PyTorch", "Keras",
            "Matplotlib", "Jupyter", "LLM", "RAG", "NLP", "CNN", "RNN", "LSTM", "transformer", "BERT", "GPT",
            "LangChain", "embeddings", "vector database",
            // Data stores
            "SQL", "MySQL", "PostgreSQL", "Oracle", "MongoDB", "Redis", "Cassandra", "Elasticsearch", "NoSQL",
            // Infrastructure and tools
            "Kafka", "RabbitMQ", "Docker", "Kubernetes", "Jenkins", "Git", "GitHub", "GitLab", "CI/CD", "AWS", "EC2",
            "S3", "Azure", "GCP", "Linux", "Agile", "Scrum", "Jira", "IntelliJ", "VS Code", "Eclipse");

    /**
     * Words that run together when written and that the speech model splits ("Java script", "hash map", "node js").
     * Each is matched with or without a space, dot or hyphen between its parts and written as here.
     */
    private static final List<String> JOINED = List.of("JavaScript", "TypeScript", "PostgreSQL", "MySQL", "MongoDB",
            "GitHub", "GitLab", "HashMap", "HashSet", "TreeMap", "ArrayList", "ConcurrentHashMap", "StringBuilder",
            "NumPy", "PyTorch", "TensorFlow", "GraphQL", "LangChain", "Node.js", "Next.js", "Express.js",
            "Vue.js", "JUnit", "OAuth", "IntelliJ", "RabbitMQ", "Elasticsearch", "WebSocket", "jQuery",
            "ExecutorService", "CompletableFuture", "NullPointerException", "NoSQL", "ReactJS");

    /** Names to capitalise when written in lower case: not English words, so "java" can only mean Java. */
    private static final List<String> CAPITALISED = List.of("Java", "Python", "Django", "Kubernetes", "Jenkins",
            "Maven", "Gradle", "Lombok", "Mockito", "Tomcat", "Redux", "Axios", "Webpack", "Keras", "Jupyter", "Kafka",
            "Redis", "Linux", "Jira", "Docker", "Hibernate");

    /** Phrases written in their product casing ("spring boot" is Spring Boot). Multi-word, so never ordinary English. */
    private static final List<String> PHRASES = List.of("Spring Boot", "Spring MVC", "Spring Security",
            "Spring Data JPA", "Core Java", "Stream API", "Tailwind CSS", "VS Code", "React Hooks", "Virtual DOM",
            "REST APIs", "REST API");

    /** Acronyms the model writes in lower case or spells out letter by letter ("j d b c", "a.p.i."). */
    private static final Set<String> ACRONYMS = Set.of("API", "APIs", "SQL", "JDBC", "JPA", "JVM", "JDK", "JRE", "JSON",
            "XML", "HTML", "CSS", "HTTP", "HTTPS", "JWT", "DOM", "JSX", "AWS", "GCP", "OOP", "OOPs", "MVC", "CRUD",
            "UI", "UX", "CI", "CD", "LLM", "NLP", "CNN", "RNN", "LSTM", "GPT", "ORM", "SDK", "URL", "IDE", "CORS",
            "AJAX", "YAML", "ES6", "EC2", "S3");

    /** Acronyms safe to uppercase when written in lower case (not English words). */
    private static final Set<String> LOWERCASE_ACRONYMS = Set.of("API", "APIs", "SQL", "JDBC", "JPA", "JVM", "JDK",
            "JRE", "JSON", "XML", "HTML", "CSS", "HTTP", "HTTPS", "JWT", "JSX", "AWS", "GCP", "MVC", "CRUD", "LLM",
            "NLP", "ORM", "SDK", "URL", "IDE", "CORS", "AJAX", "YAML", "ES6", "EC2");

    /** Known mishearings, case-insensitive, whole words. */
    private static final Map<Pattern, String> MISHEARD = new LinkedHashMap<>();

    static {
        misheard("spring boat|sprint boot|spring boots?(?= application| project| app\\b)", "Spring Boot");
        misheard("my sequel|my s\\.? ?q\\.? ?l", "MySQL");
        misheard("post ?gres(?:s)?(?: ?sql| ?s\\.? ?q\\.? ?l| sequel)?|postgre sequel|postgres sql", "PostgreSQL");
        misheard("no sequel|no s\\.? ?q\\.? ?l", "NoSQL");
        misheard("sequel(?= (?:query|queries|database|databases|server|joins?|commands?|statements?|injection))",
                "SQL");
        misheard("jason(?= (?:object|objects|format|data|file|response|request|payload|parsing|parser|string|array|web token))",
                "JSON");
        misheard("num ?pie|num py", "NumPy");
        misheard("pie ?torch|pi torch", "PyTorch");
        misheard("sci ?kit[ -]?learn|psychic learn", "scikit-learn");
        misheard("cuber ?netes|kuber ?netties|cuber ?netties|kubernities", "Kubernetes");
        misheard("jango", "Django");
        misheard("micro services", "microservices");
        misheard("micro service", "microservice");
        misheard("rest ?full|rest-full|restful", "RESTful");
        misheard("jay ?query|j query", "jQuery");
        misheard("re ?ducks", "Redux");
        misheard("get ?hub", "GitHub");
        misheard("hash ?code", "hashCode");
        misheard("java ?scripts", "JavaScript");
        misheard("react ?j\\.? ?s", "ReactJS");
        misheard("lang ?chain", "LangChain");
        misheard("type ?scripts", "TypeScript");
        misheard("es ?six|e\\.? ?s\\.? ?6", "ES6");
    }

    private static void misheard(String regex, String replacement) {
        MISHEARD.put(Pattern.compile("(?i)\\b(?:" + regex + ")\\b"), replacement);
    }

    /** "JavaScript" as a pattern that also matches "java script", "Java-Script", "javascript". */
    private static Pattern joinedPattern(String term) {
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+").matcher(term.replace(".", " "));
        while (m.find()) {
            parts.add(Pattern.quote(m.group()));
        }
        return Pattern.compile("(?i)(?<![\\w.])" + String.join("[\\s.\\-]*", parts) + "(?![\\w])");
    }

    private static final Map<Pattern, String> JOINED_PATTERNS = new LinkedHashMap<>();
    private static final Map<Pattern, String> WORD_PATTERNS = new LinkedHashMap<>();

    static {
        // Longest first, so ConcurrentHashMap is not taken as Concurrent + HashMap.
        JOINED.stream().sorted((a, b) -> b.length() - a.length())
                .forEach(term -> JOINED_PATTERNS.put(joinedPattern(term), term));
        PHRASES.forEach(phrase -> WORD_PATTERNS.put(
                Pattern.compile("(?i)\\b" + Pattern.quote(phrase).replace(" ", "\\E\\s+\\Q") + "\\b"), phrase));
        CAPITALISED.forEach(word -> WORD_PATTERNS.put(Pattern.compile("\\b" + word.toLowerCase(Locale.ROOT) + "\\b"),
                word));
        LOWERCASE_ACRONYMS.forEach(acronym -> WORD_PATTERNS.put(
                Pattern.compile("(?i)\\b" + Pattern.quote(acronym) + "\\b"), acronym));
    }

    /** Letters spoken one at a time, as the model writes them: "J D B C", "a.p.i.", "J. W. T." */
    private static final Pattern SPELLED = Pattern.compile("\\b(?:[A-Za-z]\\.?[ ]){1,4}[A-Za-z]\\b\\.?");

    /** The transcript with well-known technical mishearings and spellings put right. */
    public static String correct(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (Map.Entry<Pattern, String> e : MISHEARD.entrySet()) {
            out = e.getKey().matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        out = joinSpelledAcronyms(out);
        for (Map.Entry<Pattern, String> e : JOINED_PATTERNS.entrySet()) {
            out = e.getKey().matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        for (Map.Entry<Pattern, String> e : WORD_PATTERNS.entrySet()) {
            out = e.getKey().matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    private static String joinSpelledAcronyms(String text) {
        Matcher m = SPELLED.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String letters = m.group().replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
            String acronym = ACRONYMS.contains(letters) ? letters
                    : letters.endsWith("S") && ACRONYMS.contains(letters.substring(0, letters.length() - 1))
                            ? letters.substring(0, letters.length() - 1) + "s" : null;
            m.appendReplacement(sb, Matcher.quoteReplacement(acronym == null ? m.group() : acronym
                    + (m.group().endsWith(" ") ? " " : "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The glossary terms that appear in a text, in glossary order. */
    public static Set<String> termsIn(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        String lower = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#./]+", " ") + " ";
        String squashed = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
        for (String term : GLOSSARY) {
            String t = term.toLowerCase(Locale.ROOT);
            String sq = t.replaceAll("[^a-z0-9+#]", "");
            boolean whole = lower.contains(" " + t.replaceAll("[^a-z0-9+#./]+", " ") + " ");
            boolean joined = sq.length() >= 6 && squashed.contains(sq);
            if (whole || joined) {
                found.add(term);
            }
        }
        return found;
    }
}
