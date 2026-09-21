package ru.corelia.attachments;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.http.ApiRequest;
import ru.corelia.observability.CoreliaObservability;

import tools.jackson.databind.JsonNode;

/** API файлов и версий. Доступ к документу проверяется внутри сервиса вложений. */
@RestController
@RequestMapping("/internal/v1")
public class AttachmentController {
    private final AttachmentService attachments;
    private final ApiRequest requests;
    private final CoreliaObservability observability;

    public AttachmentController(
            AttachmentService attachments, ApiRequest requests, CoreliaObservability observability) {
        this.attachments = attachments;
        this.requests = requests;
        this.observability = observability;
    }

    @PostMapping("/initial-attachments/{id}")
    public JsonNode stage(@PathVariable String id, HttpServletRequest r) {
        return attachments.stageInitial(id, requests.body(r), requests.auth(r));
    }

    @PostMapping(value = "/staged-attachments/{id}", consumes = "multipart/form-data")
    public JsonNode stageStream(
            @PathVariable String id, @RequestParam MultipartFile file, HttpServletRequest r) {
        return attachments.stageStream(id, file, requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/attachments")
    public JsonNode list(
            @PathVariable String type,
            @PathVariable String id,
            HttpServletRequest r) {
        return array(attachments.current(type, id, requests.auth(r)));
    }

    @PostMapping("/documents/{type}/{id}/attachments")
    public ResponseEntity<JsonNode> upload(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return ResponseEntity.status(201)
                .body(upload("upload", () -> array(attachments.upload(type, id, requests.body(r), requests.auth(r)))));
    }

    @PostMapping(value = "/documents/{type}/{id}/attachments/stream", consumes = "multipart/form-data")
    public ResponseEntity<JsonNode> uploadStream(
            @PathVariable String type,
            @PathVariable String id,
            @RequestParam String requestId,
            @RequestParam MultipartFile file,
            HttpServletRequest r) {
        return ResponseEntity.status(201)
                .body(upload("upload", () -> attachments.uploadStream(type, id, requestId, file, requests.auth(r))));
    }

    @GetMapping("/attachments/{id}/metadata")
    public JsonNode metadata(@PathVariable String id, HttpServletRequest r) {
        return attachments.find(id, requests.auth(r));
    }

    @PutMapping("/attachments/{id}")
    public JsonNode replace(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return upload("replace", () -> attachments.replace(attachments.find(id, auth), requests.body(r), auth));
    }

    @PutMapping(value = "/attachments/{id}/stream", consumes = "multipart/form-data")
    public JsonNode replaceStream(
            @PathVariable String id,
            @RequestParam String requestId,
            @RequestParam MultipartFile file,
            HttpServletRequest r) {
        var auth = requests.auth(r);
        return upload("replace", () -> attachments.replaceStream(attachments.find(id, auth), requestId, file, auth));
    }

    @DeleteMapping("/attachments/{id}")
    public JsonNode delete(@PathVariable String id, HttpServletRequest r) {
        return attachments.delete(id, r.getParameter("requestId"), requests.auth(r));
    }

    @GetMapping("/attachments/{id}/versions")
    public JsonNode versions(@PathVariable String id, HttpServletRequest r) {
        return array(attachments.previous(id, requests.auth(r)));
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id, HttpServletRequest r) {
        var file = observability.observe("attachment.download", () -> attachments.download(id, requests.auth(r)));
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''" + encode(file.fileName()))
                .body(output -> {
                    try (var input = file.body()) { input.transferTo(output); }
                });
    }

    private <T> T upload(String operation, java.util.function.Supplier<T> action) {
        try {
            T result = observability.observe("attachment." + operation, action);
            observability.attachmentUploaded(operation);
            return result;
        } catch (RuntimeException error) {
            observability.attachmentUploadFailed(operation);
            throw error;
        }
    }
}
