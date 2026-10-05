package ru.corelia.attachments;

import java.time.Instant;
import java.util.UUID;

/** Метаданные неизменяемого бинарного объекта без provider-specific ссылки. */
public record Blob(
        UUID id,
        String storageProvider,
        String bucket,
        String objectKey,
        String sha256,
        long size,
        String mediaType,
        BlobState state,
        Instant createdAt,
        String createdBy,
        Instant committedAt,
        Instant deletedAt) {}
