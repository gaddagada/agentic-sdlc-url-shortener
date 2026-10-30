package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Answers to the open clarification questions (every open question must be answered).
 *
 * @param answers answer per question id, for example {@code {"q1": "..."}}
 */
public record ClarifyRequest(@NotEmpty @Size(max = 20) Map<@NotBlank @Size(max = 32) String,
        @NotBlank @Size(max = 2000) String> answers) {
}
