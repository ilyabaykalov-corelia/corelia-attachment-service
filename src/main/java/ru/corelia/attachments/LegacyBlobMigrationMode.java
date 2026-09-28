package ru.corelia.attachments;

import org.springframework.stereotype.Component;

import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;

/** Ограничивает mutation вложений на время контролируемого переноса DAM. */
@Component
public class LegacyBlobMigrationMode {
    private final boolean active;

    public LegacyBlobMigrationMode(CoreliaConfig config) {
        active = Boolean.parseBoolean(config.value("LEGACY_BLOB_MIGRATION_MODE"));
    }

    public void requireActive() {
        if (!active) throw new ApiException(409, "Для migration необходимо включить LEGACY_BLOB_MIGRATION_MODE");
    }

    public void rejectAttachmentMutation() {
        if (active) throw new ApiException(409, "Изменение вложений временно остановлено для migration");
    }
}
