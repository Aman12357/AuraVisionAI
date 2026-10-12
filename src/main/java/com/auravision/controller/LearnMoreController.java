package com.auravision.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit routes for the Learn More page. The HTML remains under
 * src/main/resources/static/learn-more.html and is packaged into the app JAR.
 */
@RestController
public class LearnMoreController {

    private static final String PAGE_PATH = "static/learn-more.html";

    @GetMapping(value = {"/learn-more", "/learn-more.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> learnMore() {
        Resource page = new ClassPathResource(PAGE_PATH);
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(page);
    }
}
