package ru.corelia.attachments;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.LegacyAttachmentEnumerator;
import ru.corelia.provider.LegacyAttachmentReferenceUpdater;
import ru.corelia.provider.PermissionProvider;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.BinaryLocation;
import ru.corelia.provider.model.BinaryStoreRequest;
import ru.corelia.provider.model.StoredFile;

/** Явно запускаемый перенос historical DAM binary в выбранное S3-compatible хранилище. */
@Service
public class LegacyBlobMigrationService {
    public record Result(int migrated) {}

    private final BinaryStorage storage;
    private final BlobRegistry blobs;
    private final List<LegacyAttachmentEnumerator> sources;
    private final List<LegacyAttachmentReferenceUpdater> references;
    private final LegacyBlobMigrationMode mode;
    private final PermissionProvider permissions;

    public LegacyBlobMigrationService(
            BinaryStorage storage,
            BlobRegistry blobs,
            List<LegacyAttachmentEnumerator> sources,
            List<LegacyAttachmentReferenceUpdater> references,
            LegacyBlobMigrationMode mode,
            PermissionProvider permissions) {
        this.storage = storage;
        this.blobs = blobs;
        this.sources = sources;
        this.references = references;
        this.mode = mode;
        this.permissions = permissions;
    }

    public Result migrateAll(AuthContext auth) {
        mode.requireActive();
        permissions.require("Attachment:migrate", auth);
        LegacyAttachmentEnumerator source = exactlyOne(sources, "источник historical вложений");
        LegacyAttachmentReferenceUpdater updater = exactlyOne(references, "обновитель historical ссылок");
        int migrated = 0;
        for (AttachmentMetadata attachment : source.historicalAttachments(auth)) {
            migrate(attachment, updater, auth);
            migrated++;
        }
        return new Result(migrated);
    }

    private void migrate(
            AttachmentMetadata attachment, LegacyAttachmentReferenceUpdater updater, AuthContext auth) {
        Path copy = null;
        Blob pending = null;
        boolean referenceUpdated = false;
        try {
            copy = Files.createTempFile("corelia-legacy-blob-", ".bin");
            Digest digest = copy(attachment, copy, auth);
            var request = new BinaryStoreRequest(
                    attachment.documentId(),
                    attachment.id(),
                    attachment.fileName(),
                    attachment.contentType(),
                    digest.size(),
                    digest.sha256());
            BinaryLocation location = storage.reserve(request, auth);
            pending = blobs.createPending(location, digest.sha256(), digest.size(), attachment.contentType(), auth.id());
            StoredFile stored;
            try (InputStream body = Files.newInputStream(copy)) {
                stored = storage.store(request.withReference(location.reference()), body, auth);
            }
            var metadata = storage.metadata(location.reference(), auth);
            if (!location.reference().equals(stored.reference())
                    || digest.size() != stored.size()
                    || !digest.sha256().equals(stored.checksum())
                    || digest.size() != metadata.size()
                    || !digest.sha256().equals(metadata.checksum()))
                throw new IllegalStateException("S3 не подтвердил перенесённое binary content");
            // Сначала сохраняем blob: после успешного ответа DataSpace нельзя допустить его GC.
            blobs.commit(pending.id());
            updater.replaceStorageReference(attachment, attachment.storageReference(), location.reference(), auth);
            referenceUpdated = true;
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось перенести historical binary", error);
        } finally {
            if (pending != null && !referenceUpdated) orphan(pending.id());
            if (copy != null) delete(copy);
        }
    }

    private Digest copy(AttachmentMetadata attachment, Path target, AuthContext auth) throws IOException {
        try (InputStream source = storage.read(attachment.storageReference(), auth);
                var output = Files.newOutputStream(target)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long size = 0;
            for (int count; (count = source.read(buffer)) != -1; ) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                size += count;
            }
            if (size != attachment.size())
                throw new IllegalStateException("Размер historical binary не совпадает с metadata");
            return new Digest(size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void orphan(UUID id) {
        try { blobs.orphan(id); } catch (IllegalStateException ignored) {
            // Blob уже мог быть зафиксирован перед обрывом ответа DataSpace; сохраняем его для reconciliation.
        }
    }

    private static void delete(Path value) {
        try { Files.deleteIfExists(value); } catch (IOException ignored) {
            // Временный файл не содержит reference и будет удалён ОС при следующей очистке.
        }
    }

    private static <T> T exactlyOne(List<T> values, String name) {
        if (values.size() != 1) throw new IllegalStateException("Требуется ровно один " + name);
        return values.getFirst();
    }

    private record Digest(long size, String sha256) {}
}
