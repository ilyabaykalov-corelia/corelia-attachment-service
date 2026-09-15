package ru.corelia.attachments;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

/** Адаптирует команды вложений к DAM и существующим GraphQL-операциям метаданных. */
@Service
public class AttachmentService {
    public record Download(byte[] body, String contentType, String fileName) {}

    private final DataSpaceClient data;
    private final FileStorageClient files;
    private final ru.corelia.transport.ServiceClient services;

    public AttachmentService(
            DataSpaceClient data,
            FileStorageClient files,
            ru.corelia.transport.ServiceClient services) {
        this.data = data;
        this.files = files;
        this.services = services;
    }

    private List<JsonNode> all(AuthContext auth) {
        List<JsonNode> result = new ArrayList<>();
        for (int offset = 0; ; ) {
            JsonNode page = query("searchAttachment", object("offset", offset, "limit", 500), auth).path("searchAttachment");
            List<JsonNode> batch = list(page.path("elems")); result.addAll(batch); offset += batch.size();
            if (offset >= number(page, "count", offset)) return result;
            if (batch.isEmpty()) throw new ApiException(502, "Неполная выборка вложений");
        }
    }

    public List<JsonNode> current(String documentType, String documentId, AuthContext auth) {
        return list(services.call("document", "/internal/v1/documents/" + encode(documentType) + "/" + encode(documentId), "GET", null, auth).path("attachments"));
    }

    public JsonNode find(String id, AuthContext auth) {
        JsonNode found =
                all(auth).stream()
                        .filter(
                                item ->
                                        id.equals(text(item, "attachmentId"))
                                                || id.equals(text(item, "id")))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(404, "Вложение не найдено"));
        requireDocument(text(found, "documentId"), auth);
        return found;
    }

    private List<JsonNode> versions(JsonNode current, AuthContext auth) {
        String logical = logicalId(current), document = text(current, "documentId");
        return all(auth).stream()
                .filter(
                        item ->
                                document.equals(text(item, "documentId"))
                                        && logical.equals(logicalId(item)))
                .toList();
    }

    public List<JsonNode> previous(String id, AuthContext auth) {
        JsonNode selected = find(id, auth);
        return versions(selected, auth).stream()
                .filter(item -> number(item, "version", 1) < number(selected, "version", 1))
                .sorted(
                        Comparator.comparingLong((JsonNode item) -> number(item, "version", 1))
                                .reversed())
                .map(AttachmentService::publicAttachment)
                .toList();
    }

    public List<JsonNode> upload(String documentType, String documentId, JsonNode payload, AuthContext auth) {
        JsonNode owner = requireDocument(documentId, auth);
        if (!documentType.equals(text(owner, "typeCode")))
            throw new ApiException(400, "Вид документа не соответствует вложению");
        List<JsonNode> items = list(payload.path("attachments"));
        if (items.isEmpty()) throw new ApiException(400, "Не переданы файлы для загрузки");
        String requestId = requireRequestId(payload);
        List<JsonNode> uploaded = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String childRequest = UUID.nameUUIDFromBytes((requestId + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            String id = UUID.nameUUIDFromBytes((documentId + ":" + childRequest).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            ObjectNode input = uploadVersion(documentId, id, id, 1, items.get(index), auth);
            uploaded.add(command(documentId, object("action", "upload", "requestId", childRequest, "file", input), auth));
        }
        return uploaded;
    }

    public JsonNode replace(JsonNode current, JsonNode payload, AuthContext auth) {
        String document = text(current, "documentId");
        String requestId = requireRequestId(payload);
        List<JsonNode> items = list(payload.path("attachments"));
        if (items.size() != 1) throw new ApiException(400, "Для замены требуется один файл");
        String id = UUID.nameUUIDFromBytes((document + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ObjectNode input = uploadVersion(document, id, logicalId(current), number(current, "version", 1) + 1, items.getFirst(), auth);
        return command(document, object("action", "replace", "requestId", requestId,
                "attachmentId", first(current, "attachmentId", "id"), "file", input), auth);
    }

    public JsonNode delete(String id, String requestId, AuthContext auth) {
        JsonNode current = find(id, auth);
        return command(text(current, "documentId"), object("action", "delete", "attachmentId", first(current, "attachmentId", "id"),
                "requestId", requireRequestId(object("requestId", requestId))), auth);
    }
    private JsonNode command(String document, JsonNode body, AuthContext auth) {
        String type = text(requireDocument(document, auth), "typeCode");
        return services.call("document", "/internal/v1/documents/" + encode(type) + "/" + encode(document) + "/attachment-commands", "POST", body, auth);
    }
    private static String requireRequestId(JsonNode payload) {
        String value = text(payload, "requestId");
        try { UUID.fromString(value); } catch (IllegalArgumentException e) { throw new ApiException(400, "Требуется requestId в формате UUID"); }
        return value;
    }

    public Download download(String id, AuthContext auth) {
        JsonNode attachment = find(id, auth);
        String reference = text(attachment, "storageReference");
        String prefix = "platform-v-dam:";
        if (!reference.startsWith(prefix)
                || reference.substring(prefix.length()).replaceAll("^/+", "").isEmpty()) {
            throw new ApiException(404, "Вложение не связано с файловым хранилищем Platform V");
        }
        var response =
                files.download(reference.substring(prefix.length()).replaceAll("^/+", ""), auth);
        return new Download(
                response.body(),
                response.headers()
                        .firstValue("content-type")
                        .orElse(
                                fallback(
                                        text(attachment, "contentType"),
                                        "application/octet-stream")),
                fallback(text(attachment, "fileName"), id));
    }

    private ObjectNode uploadVersion(
            String documentId,
            String id,
            String logicalId,
            long version,
            JsonNode item,
            AuthContext auth) {
        String name = FileStorageClient.safeFileName(text(item, "fileName")),
                base64 = text(item, "contentBase64");
        if (base64.isEmpty()) throw new ApiException(400, "Файл должен содержать имя и содержимое");
        byte[] bytes = decodeBase64(base64);
        String contentType = fallback(text(item, "contentType"), "application/octet-stream");
        String checksum;
        try { checksum = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        String path = "documents/" + documentId + "/uploads/" + id + "/" + checksum + "/" + name;
        files.upload(path, name, contentType, bytes, auth);
        return object(
                "attachmentId",
                id,
                "logicalAttachmentId",
                logicalId,
                "documentId",
                documentId,
                "fileName",
                name,
                "contentType",
                contentType,
                "size",
                bytes.length,
                "storageReference",
                "platform-v-dam:" + path,
                "version",
                version,
                "current",
                true,
                "uploadedAt",
                Instant.now().toString());
    }

    private static byte[] decodeBase64(String value) {
        // Buffer.from в Node.js допускает URL-safe алфавит, пробелы и отсутствующий padding.
        String cleaned =
                value.replace('-', '+').replace('_', '/').replaceAll("[^A-Za-z0-9+/=]", "");
        int padding = cleaned.indexOf('=');
        if (padding >= 0) cleaned = cleaned.substring(0, padding);
        if (cleaned.length() % 4 == 1) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return Base64.getDecoder().decode(cleaned);
    }

    private static String logicalId(JsonNode raw) {
        return first(raw, "logicalAttachmentId", "attachmentId", "id");
    }

    private static JsonNode publicAttachment(JsonNode raw) {
        return object(
                "id",
                first(raw, "attachmentId", "id"),
                "logicalAttachmentId",
                logicalId(raw),
                "documentId",
                text(raw, "documentId"),
                "fileName",
                text(raw, "fileName"),
                "contentType",
                fallback(text(raw, "contentType"), "application/octet-stream"),
                "size",
                number(raw, "size", 0),
                "version",
                number(raw, "version", 1),
                "current",
                !raw.path("current").equals(MAPPER.getNodeFactory().booleanNode(false)),
                "uploadedAt",
                text(raw, "uploadedAt"));
    }

    /**
     * Проверяет доступ к карточке до обращения к бинарному содержимому или изменения метаданных.
     */
    private JsonNode requireDocument(String id, AuthContext auth) {
        if (id.isEmpty()) throw new ApiException(502, "Вложение не связано с документом");
        try {
            return services.call(
                    "document",
                    "/internal/v1/documents/by-id/" + encode(id),
                    "GET",
                    null,
                    auth);
        } catch (ApiException error) {
            if (error.status() == 404) throw new ApiException(404, "Документ вложения не найден");
            throw error;
        }
    }

    private JsonNode query(String name, JsonNode variables, AuthContext auth) {
        return data.query(name, variables, auth);
    }
}
