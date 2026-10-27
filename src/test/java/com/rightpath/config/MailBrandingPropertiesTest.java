package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Every environment supplies the candidate-facing mail branding.
 *
 * <p>Each of these keys has a code-side default in {@code EmailServiceImpl}, so
 * a block nested one level wrong does not fail startup — it silently serves the
 * production wording and a production portal link from every environment. That
 * is the failure this guards: the assertions below read the real yml files and
 * check the exact property paths the {@code @Value} annotations name.</p>
 */
class MailBrandingPropertiesTest {

    /** The keys EmailServiceImpl resolves. Kept in the order it declares them. */
    private static final List<String> MAIL_KEYS = List.of(
            "app.mail.from-name",
            "app.mail.company-name",
            "app.mail.support-email",
            "app.mail.website",
            "app.mail.company-address",
            "app.mail.candidate-portal-url",
            "app.mail.test-venue");

    /**
     * The value that actually renders, given no environment override.
     *
     * <p>Half these files write {@code ${VAR:default}} so a deployment can
     * override them. Comparing those raw strings compares variable names, not
     * addresses — so this unwraps to the default, which is what a deployment
     * that sets nothing will send.</p>
     */
    private static String effectiveValue(Object raw) {
        String value = String.valueOf(raw);
        if (value.startsWith("${") && value.endsWith("}")) {
            int colon = value.indexOf(':');
            return colon < 0 ? "" : value.substring(colon + 1, value.length() - 1);
        }
        return value;
    }

    private PropertySource<?> load(String profile) throws IOException {
        ClassPathResource resource = new ClassPathResource("application-" + profile + ".yml");
        assertTrue(resource.exists(), () -> "missing application-" + profile + ".yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application-" + profile, resource);
        assertEquals(1, sources.size(), "expected a single document per profile yml");
        return sources.get(0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev", "stage", "uat", "prod" })
    void everyProfileDefinesEveryMailKey(String profile) throws IOException {
        PropertySource<?> source = load(profile);

        for (String key : MAIL_KEYS) {
            Object value = source.getProperty(key);
            assertNotNull(value, () -> profile + " is missing " + key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev", "stage", "uat", "prod" })
    void portalLinkIsAnAbsoluteUrl(String profile) throws IOException {
        // A relative or empty portal link produces a dead button in a mail that
        // has already been sent, so it is worth pinning per environment.
        String portal = effectiveValue(load(profile).getProperty("app.mail.candidate-portal-url"));
        assertTrue(
                portal.contains("http://") || portal.contains("https://"),
                () -> profile + " portal url is not absolute: " + portal);
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev", "stage", "uat", "prod" })
    void venueAndAddressAgree(String profile) throws IOException {
        // EmailServiceImpl quotes the venue on slot mails and the address in the
        // footer; two spellings of one office reads as two offices.
        PropertySource<?> source = load(profile);
        assertEquals(
                effectiveValue(source.getProperty("app.mail.company-address")),
                effectiveValue(source.getProperty("app.mail.test-venue")),
                () -> profile + " quotes a different address and test venue");
    }

    @Test
    void onlyProductionSendsUnmarkedMail() throws IOException {
        // A staging mail that is indistinguishable from a real one is how a test
        // candidate ends up acting on it. Non-production senders say so.
        for (String profile : List.of("dev", "stage", "uat")) {
            String fromName = effectiveValue(load(profile).getProperty("app.mail.from-name"));
            assertTrue(
                    fromName.toUpperCase().contains(profile.toUpperCase()),
                    () -> profile + " sender name should mark itself non-production: " + fromName);
        }

        String prodFromName = effectiveValue(load("prod").getProperty("app.mail.from-name"));
        assertFalse(
                prodFromName.contains("(") ,
                () -> "production sender name should carry no environment marker: " + prodFromName);
    }

    @Test
    void productionPortalIsNotALocalhostOrNonProdHost() throws IOException {
        String portal = effectiveValue(load("prod").getProperty("app.mail.candidate-portal-url"));
        assertFalse(portal.contains("localhost"), portal);
        assertFalse(portal.contains("stage."), portal);
        assertFalse(portal.contains("uat."), portal);
        assertFalse(portal.contains("dev."), portal);
    }
}
