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
        return list(
                query("searchAttachment", object(), auth).path("searchAttachment").path("elems"));
    }

    public List<JsonNode> current(String documentId, AuthContext auth) {
        requireDocument(documentId, auth);
        return all(auth).stream()
                .filter(
                        item ->
                                documentId.equals(text(item, "documentId"))
                                        && !item.path("current")
                                                .equals(MAPPER.getNodeFactory().booleanNode(false)))
                .sorted(
                        Comparator.comparing((JsonNode node) -> text(node, "uploadedAt"))
                                .reversed())
                .map(AttachmentService::publicAttachment)
                .toList();
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
        return versions(find(id, auth), auth).stream()
                .filter(
                        item ->
                                item.path("current")
                                        .equals(MAPPER.getNodeFactory().booleanNode(false)))
                .sorted(
                        Comparator.comparingLong((JsonNode item) -> number(item, "version", 1))
                                .reversed())
                .map(AttachmentService::publicAttachment)
                .toList();
    }

    public List<JsonNode> upload(String documentId, JsonNode payload, AuthContext auth) {
        JsonNode documentRecord = requireDocumentRecord(documentId, auth);
        List<JsonNode> items = list(payload.path("attachments"));
        if (items.isEmpty()) throw new ApiException(400, "Не переданы файлы для загрузки");
        List<JsonNode> uploaded = new ArrayList<>();
        for (JsonNode item : items) {
            String id = UUID.randomUUID().toString();
            ObjectNode input = uploadVersion(documentId, id, id, 1, item, auth);
            JsonNode result = query("createAttachment", compositionVariables(documentRecord, input, false), auth);
            uploaded.add(publicAttachment(result.path("packet").path("createAttachment")));
        }
        return uploaded;
    }

    public JsonNode replace(JsonNode current, JsonNode payload, AuthContext auth) {
        String document = text(current, "documentId");
        if (document.isEmpty())
            throw new ApiException(502, "DataSpace вернул вложение без documentId");
        List<JsonNode> items = list(payload.path("attachments"));
        if (items.isEmpty()) throw new ApiException(400, "Не передан файл для загрузки");
        ObjectNode input =
                uploadVersion(
                        document,
                        UUID.randomUUID().toString(),
                        logicalId(current),
                        Math.max(1, number(current, "version", 1)) + 1,
                        items.getFirst(),
                        auth);
        JsonNode documentRecord = requireDocumentRecord(document, auth);
        String changeId = UUID.randomUUID().toString();
        JsonNode result =
                query(
                        "replaceAttachmentVersion",
                        object("currentAttachmentId", text(current, "id"), "input", input,
                                "changeId", changeId, "contractId", text(documentRecord, "dataSpaceId"),
                                "change", change(documentRecord, input, false, changeId)),
                        auth);
        return publicAttachment(result.path("packet").path("createAttachment"));
    }

    public JsonNode delete(String id, AuthContext auth) {
        JsonNode current = find(id, auth);
        String document = text(current, "documentId");
        if (document.isEmpty())
            throw new ApiException(502, "DataSpace вернул вложение без documentId");
        JsonNode documentRecord = requireDocumentRecord(document, auth);
        String changeId = UUID.randomUUID().toString();
        query("deleteAttachment", object("id", text(current, "id"), "documentId", document,
                "changeId", changeId, "contractId", text(documentRecord, "dataSpaceId"),
                "change", change(documentRecord, current, true, changeId)), auth);
        return object("deleted", true);
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
        String path = "documents/" + documentId + "/" + logicalId + "/v" + version + "/" + name;
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
                (long) bytes.length,
                "storageReference",
                "platform-v-dam:" + path,
                "version",
                Math.toIntExact(version),
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
    private void requireDocument(String id, AuthContext auth) {
        if (id.isEmpty()) throw new ApiException(502, "Вложение не связано с документом");
        try {
            services.call(
                    "document",
                    "/internal/v1/documents/PDS_CONTRACT/" + encode(id),
                    "GET",
                    null,
                    auth);
        } catch (ApiException error) {
            if (error.status() == 404) throw new ApiException(404, "Документ вложения не найден");
            throw error;
        }
    }

    private JsonNode requireDocumentRecord(String id, AuthContext auth) {
        if (id.isEmpty()) throw new ApiException(502, "Вложение не связано с документом");
        return services.call("document", "/internal/v1/documents/PDS_CONTRACT/" + encode(id), "GET", null, auth);
    }

    private static JsonNode compositionVariables(JsonNode document, JsonNode attachment, boolean removed) {
        String changeId = UUID.randomUUID().toString();
        return object("input", attachment, "changeId", changeId, "contractId", text(document, "dataSpaceId"),
                "change", change(document, attachment, removed, changeId));
    }

    private static JsonNode change(JsonNode document, JsonNode attachment, boolean removed, String requestedId) {
        String head = text(document, "attachmentsHead");
        String changeId = requestedId == null ? UUID.randomUUID().toString() : requestedId;
        return object("changeKey", changeId,
                "documentId", fallback(text(document, "documentId"), text(document, "id")),
                "documentType", fallback(text(document, "typeCode"), "PDS_CONTRACT"),
                "previousId", head,
                "previousKey", head.isEmpty()
                        ? fallback(text(document, "documentId"), text(document, "id"))
                        : head,
                "attachmentId", first(attachment, "id", "attachmentId"), "removed", removed);
    }

    private JsonNode query(String name, JsonNode variables, AuthContext auth) {
        return data.query(name, variables, auth);
    }
}
