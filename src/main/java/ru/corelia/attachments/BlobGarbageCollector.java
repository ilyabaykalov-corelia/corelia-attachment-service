package ru.corelia.attachments;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.corelia.config.CoreliaConfig;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.model.StorageReference;

/** Повторяемо удаляет незафиксированные blob-ы после истечения времени восстановления. */
@Component
public class BlobGarbageCollector {
    private final BlobRegistry blobs;
    private final BinaryStorage storage;
    private final long recoveryHours;

    public BlobGarbageCollector(BlobRegistry blobs, BinaryStorage storage, CoreliaConfig config) {
        this.blobs = blobs;
        this.storage = storage;
        recoveryHours = config.number("BLOB_GC_RECOVERY_HOURS", 24);
        if (recoveryHours < 1 || recoveryHours > 24 * 30)
            throw new IllegalArgumentException("BLOB_GC_RECOVERY_HOURS должен быть от 1 до 720");
    }

    @Scheduled(fixedDelayString = "${BLOB_GC_INTERVAL:PT5M}")
    public void collect() {
        Instant cutoff = Instant.now().minus(recoveryHours, ChronoUnit.HOURS);
        for (Blob pending : blobs.stale(BlobState.PENDING, cutoff)) {
            try { blobs.orphan(pending.id()); } catch (IllegalStateException ignored) {
                // Параллельная upload saga уже зафиксировала состояние blob-а.
            }
        }
        delete(blobs.stale(BlobState.ORPHANED, cutoff), true);
        delete(blobs.stale(BlobState.DELETING, cutoff), false);
    }

    private void delete(java.util.List<Blob> values, boolean begin) {
        for (Blob blob : values) {
            try {
                if (begin) blobs.beginDeleting(blob.id());
                storage.delete(new StorageReference("corelia-blob://" + blob.id()), null);
                blobs.markDeleted(blob.id());
            } catch (RuntimeException ignored) {
                // Состояние DELETING намеренно сохраняется для следующей попытки GC.
            }
        }
    }
}
