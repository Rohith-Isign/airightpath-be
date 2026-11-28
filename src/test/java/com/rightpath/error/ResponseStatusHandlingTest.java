package com.rightpath.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class ResponseStatusHandlingTest {

    @RestController
    static class FinishedUploadController {
        @PostMapping("/part")
        void part() {
            throw new ResponseStatusException(HttpStatus.GONE, "This recording has already been finished");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FinishedUploadController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void keepsTheStatusTheExceptionNames() throws Exception {
        mvc.perform(post("/part").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("This recording has already been finished"));
    }
}
