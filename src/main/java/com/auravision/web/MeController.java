package com.auravision.web;

import com.auravision.service.GenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** The Studio header reads the visitor's plan and remaining daily quota from here. */
@RestController
@RequiredArgsConstructor
public class MeController {
    private final GenerationService service;

    @GetMapping("/api/me")
    public GenerationService.Usage me(@RequestAttribute(OwnerFilter.ATTR) String owner) {
        return service.usage(owner);
    }
}
