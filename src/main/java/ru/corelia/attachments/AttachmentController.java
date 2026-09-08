package ru.corelia.attachments;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;

import tools.jackson.databind.JsonNode;

/** API файлов и версий. Доступ к документу проверяется внутри сервиса вложений. */
@RestController
@RequestMapping("/internal/v1")
public class AttachmentController {
    private final AttachmentService attachments;
    private final ApiRequest requests;

    public AttachmentController(AttachmentService attachments, ApiRequest requests) {
        this.attachments = attachments;
        this.requests = requests;
    }

    @GetMapping("/documents/{id}/attachments")
    public JsonNode list(@PathVariable String id, HttpServletRequest r) {
        return array(attachments.current(id, requests.auth(r)));
    }

    @PostMapping("/documents/{id}/attachments")
    public ResponseEntity<JsonNode> upload(@PathVariable String id, HttpServletRequest r) {
        return ResponseEntity.status(201)
                .body(array(attachments.upload(id, requests.body(r), requests.auth(r))));
    }

    @GetMapping("/attachments/{id}/metadata")
    public JsonNode metadata(@PathVariable String id, HttpServletRequest r) {
        return attachments.find(id, requests.auth(r));
    }

    @PutMapping("/attachments/{id}")
    public JsonNode replace(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return attachments.replace(attachments.find(id, auth), requests.body(r), auth);
    }

    @DeleteMapping("/attachments/{id}")
    public JsonNode delete(@PathVariable String id, HttpServletRequest r) {
        return attachments.delete(id, requests.auth(r));
    }

    @GetMapping("/attachments/{id}/versions")
    public JsonNode versions(@PathVariable String id, HttpServletRequest r) {
        return array(attachments.previous(id, requests.auth(r)));
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id, HttpServletRequest r) {
        var file = attachments.download(id, requests.auth(r));
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''" + encode(file.fileName()))
                .body(file.body());
    }
}
