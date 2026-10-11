package com.auravision.web;

import com.auravision.model.Generation;
import com.auravision.service.GenerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
public class GenerationController {
    private final GenerationService service;

    public record CreateRequest(@NotNull Generation.Type type,
                                @NotBlank @Size(max = 1500) String prompt,
                                String aspectRatio, String model) {}

    /** What the browser sees. File paths and operation ids stay on the server. */
    public record View(Long id, Generation.Type type, String prompt, String aspectRatio, String model,
                       Generation.Status status, String error, Instant createdAt, String mediaUrl) {
        static View of(Generation g) {
            return new View(g.getId(), g.getType(), g.getPrompt(), g.getAspectRatio(), g.getModel(), g.getStatus(),
                    g.getError(), g.getCreatedAt(),
                    g.getStatus() == Generation.Status.DONE ? "/api/generations/" + g.getId() + "/media" : null);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View create(@RequestAttribute(OwnerFilter.ATTR) String owner, @Valid @RequestBody CreateRequest req) {
        return View.of(service.submit(owner, req.type(), req.prompt(), req.aspectRatio(), req.model()));
    }

    @GetMapping
    public List<View> list(@RequestAttribute(OwnerFilter.ATTR) String owner) {
        return service.latest(owner).stream().map(View::of).toList();
    }

    @GetMapping("/{id}")
    public View one(@RequestAttribute(OwnerFilter.ATTR) String owner, @PathVariable Long id) {
        return View.of(service.get(owner, id));
    }

    @GetMapping("/{id}/media")
    public ResponseEntity<Resource> media(@RequestAttribute(OwnerFilter.ATTR) String owner, @PathVariable Long id,
                                          @RequestParam(defaultValue = "false") boolean download) {
        Generation g = service.get(owner, id);
        if (g.getStatus() != Generation.Status.DONE || g.getFilePath() == null || !Files.exists(Path.of(g.getFilePath())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not available");
        String ext = g.getFilePath().substring(g.getFilePath().lastIndexOf('.'));
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(g.getMimeType()));
        if (download) b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auravision-" + id + ext + "\"");
        return b.body(new FileSystemResource(g.getFilePath()));   // Spring handles Range requests, so video seeking works
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(OwnerFilter.ATTR) String owner, @PathVariable Long id) {
        service.delete(owner, id);
    }
}
