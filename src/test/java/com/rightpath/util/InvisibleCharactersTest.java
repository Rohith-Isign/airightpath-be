package com.rightpath.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.config.InvisibleCharacterConfig;

/** A field holding only invisible characters looked empty but passed "required". It arrives empty now. */
class InvisibleCharactersTest {

    @Test
    void invisibleCharactersAreRemoved() {
        assertThat(InvisibleCharacters.strip("\u200B")).isEmpty();
        assertThat(InvisibleCharacters.strip("\u3164\u2800")).isEmpty();
        assertThat(InvisibleCharacters.strip("\uFEFFSenior\u200B Engineer")).isEqualTo("Senior Engineer");
        assertThat(InvisibleCharacters.strip("x\u00A0y")).isEqualTo("x y");
        assertThat(InvisibleCharacters.strip("a\u200Db")).isEqualTo("ab");
        assertThat(InvisibleCharacters.isBlank("\u200B\u200B ")).isTrue();
        assertThat(InvisibleCharacters.isBlank("Java")).isFalse();
    }

    @Test
    void joinersThatDoRealWorkAreKept() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69";
        assertThat(InvisibleCharacters.strip(family)).isEqualTo(family);
        String hindi = "\u0928\u092E\u200D\u0938\u094D";
        assertThat(InvisibleCharacters.strip(hindi)).isEqualTo(hindi);
    }

    @Test
    void jsonRequestsArriveClean() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new InvisibleCharacterConfig().invisibleCharacterModule());
        Map<?, ?> body = mapper.readValue("{\"jobDescription\":\"\u200B\",\"title\":\"Dev\u200Beloper\"}", Map.class);
        assertThat(body.get("jobDescription")).isEqualTo("");
        assertThat(body.get("title")).isEqualTo("Developer");
    }
}
