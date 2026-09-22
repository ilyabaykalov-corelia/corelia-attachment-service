package ru.corelia.attachments;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.AttachmentCatalog;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.BinaryStoreRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.io.*;
import java.util.*;

/** Обрабатывает команды вложений через нейтральные возможности выбранного provider. */
@Service
public class AttachmentService {
    public record Download(InputStream body, String contentType, String fileName) {}

    private final AttachmentCatalog catalog;
    private final BinaryStorage files;
    private final ru.corelia.transport.ServiceClient services;
    private final DocumentTypeCatalog documentTypes;
    private final long maxAttachmentBytes;

    public AttachmentService(
            AttachmentCatalog catalog,
            BinaryStorage files,
            ru.corelia.transport.ServiceClient services,
            CoreliaConfig config,
            DocumentTypeCatalog documentTypes) {
        this.catalog = catalog;
        this.files = files;
        this.services = services;
        this.documentTypes = documentTypes;
        long megabytes = config.number("MAX_ATTACHMENT_SIZE_MB", 10);
        if (megabytes < 1 || megabytes > 1024)
            throw new IllegalArgumentException("MAX_ATTACHMENT_SIZE_MB должен быть от 1 до 1024");
        this.maxAttachmentBytes = megabytes * 1024 * 1024;
    }


    public List<JsonNode> current(String documentType, String documentId, AuthContext auth) {
        return list(services.call("document", "/internal/v1/documents/" + encode(documentType) + "/" + encode(documentId), "GET", null, auth).path("attachments"));
    }

    public JsonNode find(String id, AuthContext auth) {
        JsonNode found = metadata(catalog.find(id, auth));
        requireDocument(text(found, "documentId"), auth);
        return found;
    }

    private List<JsonNode> versions(JsonNode current, AuthContext auth) {
        String logical = logicalId(current), document = text(current, "documentId");
        return catalog.attachmentVersions(first(current, "attachmentId", "id"), auth).stream().map(AttachmentService::metadata).toList();
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
            ObjectNode input = uploadVersion(documentId, id, id, 1, items.get(index), policy(documentType), auth);
            uploaded.add(commitUploadAndStartIfReady(documentType, documentId, childRequest, input, auth));
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
        ObjectNode input = uploadStreamVersion(documentId, id, id, 1, file, policy(documentType), auth);
        return commitUploadAndStartIfReady(documentType, documentId, requestId, input, auth);
    }

    public JsonNode replace(JsonNode current, JsonNode payload, AuthContext auth) {
        String document = text(current, "documentId");
        String requestId = requireRequestId(payload);
        List<JsonNode> items = list(payload.path("attachments"));
        if (items.size() != 1) throw new ApiException(400, "Для замены требуется один файл");
        String id = UUID.nameUUIDFromBytes((document + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String documentType = text(requireDocument(document, auth), "typeCode");
        ObjectNode input = uploadVersion(document, id, logicalId(current), number(current, "version", 1) + 1, items.getFirst(), policy(documentType), auth);
        return command(document, object("action", "replace", "requestId", requestId,
                "attachmentId", first(current, "attachmentId", "id"), "file", input), auth);
    }

    public JsonNode replaceStream(
            JsonNode current, String requestId, MultipartFile file, AuthContext auth) {
        String document = text(current, "documentId");
        requireRequestId(object("requestId", requestId));
        if (file.isEmpty()) throw new ApiException(400, "Не передан файл для замены");
        String id = UUID.nameUUIDFromBytes((document + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String documentType = text(requireDocument(document, auth), "typeCode");
        ObjectNode input =
                uploadStreamVersion(
                        document,
                        id,
                        logicalId(current),
                        number(current, "version", 1) + 1,
                        file, policy(documentType),
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
    private JsonNode commitUploadAndStartIfReady(
            String type, String document, String requestId, ObjectNode input, AuthContext auth) {
        JsonNode committed = command(document, object("action", "upload", "requestId", requestId, "file", input), auth);
        services.call("document", "/internal/v1/documents/" + encode(type) + "/" + encode(document) + "/workflow-readiness", "POST", object(), auth);
        return committed;
    }
    private static String requireRequestId(JsonNode payload) {
        String value = text(payload, "requestId");
        try { UUID.fromString(value); } catch (IllegalArgumentException e) { throw new ApiException(400, "Требуется requestId в формате UUID"); }
        return value;
    }

    public Download download(String id, AuthContext auth) {
        JsonNode attachment = find(id, auth);
        var response = files.read(new ru.corelia.provider.model.StorageReference(text(attachment, "storageReference")), auth);
        return new Download(
                response,
                fallback(text(attachment, "contentType"), "application/octet-stream"),
                fallback(text(attachment, "fileName"), id));
    }

    /** Подготовка обязательного первого файла до появления документа; только для document-service. */
    public JsonNode stageInitial(String documentId, JsonNode body, AuthContext auth) {
        try { UUID.fromString(documentId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректный ID документа"); }
        // mTLS ограничивает этот маршрут document-service, который владеет авторизацией создания.
        JsonNode item = body.path("attachment");
        String content = text(item, "contentBase64");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(content); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректное содержимое файла"); }
        if (bytes.length == 0) throw new ApiException(400, "Для создания документа требуется непустое вложение");
        String id = UUID.nameUUIDFromBytes((documentId + ":initial").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return uploadVersion(documentId, id, id, 1, item, defaultPolicy(), auth);
    }

    /** Подготавливает первый файл создаваемого документа до запуска процесса. */
    public JsonNode stageStream(String documentId, MultipartFile file, AuthContext auth) {
        try { UUID.fromString(documentId); } catch (IllegalArgumentException e) { throw new ApiException(400, "Некорректный ID документа"); }
        if (file.isEmpty()) throw new ApiException(400, "Для создания документа требуется непустое вложение");
        String id = UUID.nameUUIDFromBytes((documentId + ":initial").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return uploadStreamVersion(documentId, id, id, 1, file, defaultPolicy(), auth);
    }

    private ObjectNode uploadVersion(
            String documentId,
            String id,
            String logicalId,
            long version,
            JsonNode item,
            AttachmentPolicy policy,
            AuthContext auth) {
        String name = ru.corelia.support.FileNames.safe(text(item, "fileName")),
                base64 = text(item, "contentBase64");
        if (base64.isEmpty()) throw new ApiException(400, "Файл должен содержать имя и содержимое");
        validateExtension(name, policy);
        if ((long) base64.length() * 3 / 4 > policy.maxBytes())
            throw new ApiException(413, "Превышен допустимый размер вложения");
        byte[] bytes = decodeBase64(base64);
        if (bytes.length > policy.maxBytes())
            throw new ApiException(413, "Превышен допустимый размер вложения");
        String contentType = fallback(text(item, "contentType"), "application/octet-stream");
        String checksum;
        try { checksum = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var stored = files.store(
                new BinaryStoreRequest(documentId, id, name, contentType, bytes.length, checksum),
                new ByteArrayInputStream(bytes),
                auth);
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
                "storageReference", stored.reference().value(),
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
            AttachmentPolicy policy,
            AuthContext auth) {
        String name = ru.corelia.support.FileNames.safe(fallback(file.getOriginalFilename(), "attachment.bin"));
        long size = file.getSize();
        if (size <= 0) throw new ApiException(400, "Файл не должен быть пустым");
        validateExtension(name, policy);
        if (size > policy.maxBytes()) throw new ApiException(413, "Превышен допустимый размер вложения");
        String checksum = checksum(file, size);
        String contentType = fallback(file.getContentType(), "application/octet-stream");
        ru.corelia.provider.model.StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = files.store(
                    new BinaryStoreRequest(documentId, id, name, contentType, size, checksum), content, auth);
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
                "storageReference", stored.reference().value(),
                "version", version,
                "current", true,
                "uploadedAt", Instant.now().toString());
    }

    private AttachmentPolicy policy(String documentType) {
        JsonNode attachment = documentTypes.definition(documentType).attachments();
        var extensions = new HashSet<String>();
        for (JsonNode extension : attachment.path("allowedExtensions")) extensions.add(extension.asString());
        return new AttachmentPolicy(attachment.path("maxSizeBytes").asLong(maxAttachmentBytes), extensions);
    }

    private AttachmentPolicy defaultPolicy() { return new AttachmentPolicy(maxAttachmentBytes, Set.of()); }

    private static void validateExtension(String name, AttachmentPolicy policy) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!policy.allowedExtensions().isEmpty() && !policy.allowedExtensions().contains(extension)) throw new ApiException(400, "Недопустимый формат вложения");
    }

    private record AttachmentPolicy(long maxBytes, Set<String> allowedExtensions) {}

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

    private static JsonNode metadata(AttachmentMetadata value) { return object("attachmentId", value.id(), "logicalAttachmentId", value.logicalId(), "documentId", value.documentId(), "fileName", value.fileName(), "contentType", value.contentType(), "size", value.size(), "version", value.version(), "current", value.current(), "uploadedAt", value.uploadedAt() == null ? "" : value.uploadedAt().toString(), "storageReference", value.storageReference().value()); }
}
