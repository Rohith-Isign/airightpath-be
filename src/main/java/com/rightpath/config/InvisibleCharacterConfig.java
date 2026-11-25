package com.rightpath.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.rightpath.util.InvisibleCharacters;

/**
 * Every text value in every JSON request reaches the application without invisible characters.
 *
 * <p>Registered as a Jackson module, which Spring Boot adds to the mapper it uses for request bodies. A field sent as
 * nothing but a zero-width space arrives empty, so {@code @NotBlank} and the services' own "required" checks refuse
 * it as they would an empty field.</p>
 */
@Configuration
public class InvisibleCharacterConfig {

    @Bean
    public Module invisibleCharacterModule() {
        SimpleModule module = new SimpleModule("InvisibleCharacters");
        module.addDeserializer(String.class, new StdScalarDeserializer<String>(String.class) {
            private static final long serialVersionUID = 1L;

            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return InvisibleCharacters.strip(StringDeserializer.instance.deserialize(parser, context));
            }
        });
        return module;
    }
}
