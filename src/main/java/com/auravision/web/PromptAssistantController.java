package com.auravision.web;

import com.auravision.service.GeminiClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Text-only helper endpoint. Image generation remains on /api/generations. */
@RestController
@RequestMapping("/api/prompt-assistant")
@RequiredArgsConstructor
public class PromptAssistantController {
    private final GeminiClient gemini;

    public record EnhanceRequest(@NotBlank @Size(max = 1500) String prompt) {}
    public record EnhanceResponse(String enhancedPrompt, String model) {}

    @PostMapping("/enhance")
    public EnhanceResponse enhance(
            @RequestAttribute(OwnerFilter.ATTR) String owner,
            @Valid @RequestBody EnhanceRequest request) {
        try {
            String improved = gemini.enhanceImagePrompt(request.prompt().trim());
            return new EnhanceResponse(improved, gemini.textModel());
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) message = "Gemini prompt enhancement failed.";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
    }
}
