package ru.corelia.attachments;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.io.*;
import java.util.*;

/** Адаптирует команды вложений к DAM и существующим GraphQL-операциям метаданных. */
@Service
public class AttachmentService {
    public record Download(byte[] body, String contentType, String fileName) {}

    private final DataSpaceClient data;
    private final FileStorageClient files;
    private final ru.corelia.transport.ServiceClient services;
    private final long maxAttachmentBytes;

    public AttachmentService(
            DataSpaceClient data,
            FileStorageClient files,
            ru.corelia.transport.ServiceClient services,
            CoreliaConfig config) {
        this.data = data;
        this.files = files;
        this.services = services;
        long megabytes = config.number("MAX_ATTACHMENT_SIZE_MB", 10);
        if (megabytes < 1 || megabytes > 1024)
            throw new IllegalArgumentException("MAX_ATTACHMENT_SIZE_MB должен быть от 1 до 1024");
        this.maxAttachmentBytes = megabytes * 1024 * 1024;
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

    public JsonNode uploadStream(
            String documentType,
            String documentId,
            String requestId,
            MultipartFile file,
            AuthContext auth) {
        JsonNode owner = requireDocument(documentId, auth);
        if (!documentType.equals(text(owner, "typeCode")))
            throw new ApiException(400, "Вид документа не соответствует вложению");
        requireRequestId(object("requestId", requestId));
        if (file.isEmpty()) throw new ApiException(400, "Не передан файл для загрузки");
        String id = UUID.nameUUIDFromBytes((documentId + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ObjectNode input = uploadStreamVersion(documentId, id, id, 1, file, auth);
        return command(documentId, object("action", "upload", "requestId", requestId, "file", input), auth);
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

    public JsonNode replaceStream(
            JsonNode current, String requestId, MultipartFile file, AuthContext auth) {
        String document = text(current, "documentId");
        requireRequestId(object("requestId", requestId));
        if (file.isEmpty()) throw new ApiException(400, "Не передан файл для замены");
        String id = UUID.nameUUIDFromBytes((document + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ObjectNode input =
                uploadStreamVersion(
                        document,
                        id,
                        logicalId(current),
                        number(current, "version", 1) + 1,
                        file,
                        auth);
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

    /** Подготовка обязательного первого файла до появления документа; только для document-service. */
    public JsonNode stageInitial(String documentId, JsonNode body, AuthContext auth) {
        try { UUID.fromString(documentId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректный ID документа"); }
        // mTLS restricts this route to document-service, which owns creation authorization.
        JsonNode item = body.path("attachment");
        String content = text(item, "contentBase64");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(content); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректное содержимое файла"); }
        if (bytes.length == 0) throw new ApiException(400, "Для создания документа требуется непустое вложение");
        String id = UUID.nameUUIDFromBytes((documentId + ":initial").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return uploadVersion(documentId, id, id, 1, item, auth);
    }

    /** Подготавливает первый файл создаваемого документа до запуска процесса. */
    public JsonNode stageStream(String documentId, MultipartFile file, AuthContext auth) {
        try { UUID.fromString(documentId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректный ID документа"); }
        if (file.isEmpty()) throw new ApiException(400, "Для создания документа требуется непустое вложение");
        String id = UUID.nameUUIDFromBytes((documentId + ":initial").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return uploadStreamVersion(documentId, id, id, 1, file, auth);
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
        if ((long) base64.length() * 3 / 4 > maxAttachmentBytes)
            throw new ApiException(413, "Превышен допустимый размер вложения");
        byte[] bytes = decodeBase64(base64);
        if (bytes.length > maxAttachmentBytes)
            throw new ApiException(413, "Превышен допустимый размер вложения");
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

    private ObjectNode uploadStreamVersion(
            String documentId,
            String id,
            String logicalId,
            long version,
            MultipartFile file,
            AuthContext auth) {
        String name = FileStorageClient.safeFileName(fallback(file.getOriginalFilename(), "attachment.bin"));
        long size = file.getSize();
        if (size <= 0) throw new ApiException(400, "Файл не должен быть пустым");
        if (size > maxAttachmentBytes) throw new ApiException(413, "Превышен допустимый размер вложения");
        String checksum = checksum(file, size);
        String contentType = fallback(file.getContentType(), "application/octet-stream");
        String path = "documents/" + documentId + "/uploads/" + id + "/" + checksum + "/" + name;
        try (InputStream content = file.getInputStream()) {
            files.upload(path, name, contentType, content, size, auth);
        } catch (IOException error) {
            throw new ApiException(400, "Не удалось прочитать загружаемый файл");
        }
        return object(
                "attachmentId", id,
                "logicalAttachmentId", logicalId,
                "documentId", documentId,
                "fileName", name,
                "contentType", contentType,
                "size", size,
                "storageReference", "platform-v-dam:" + path,
                "version", version,
                "current", true,
                "uploadedAt", Instant.now().toString());
    }

    private String checksum(MultipartFile file, long expectedSize) {
        try (InputStream content = file.getInputStream()) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long actualSize = 0;
            for (int count; (count = content.read(buffer)) != -1; ) {
                actualSize += count;
                if (actualSize > maxAttachmentBytes) throw new ApiException(413, "Превышен допустимый размер вложения");
                digest.update(buffer, 0, count);
            }
            if (actualSize != expectedSize) throw new ApiException(400, "Размер загружаемого файла изменился");
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new ApiException(400, "Не удалось прочитать загружаемый файл");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] decodeBase64(String value) {
        boolean urlSafe = value.indexOf('-') >= 0 || value.indexOf('_') >= 0;
        String pattern = urlSafe ? "[A-Za-z0-9_-]*={0,2}" : "[A-Za-z0-9+/]*={0,2}";
        if (!value.matches(pattern) || value.indexOf('=') >= 0 && value.indexOf('=') < value.length() - 2)
            throw new ApiException(400, "Некорректное содержимое файла");
        try {
            return (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(value);
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "Некорректное содержимое файла");
        }
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
