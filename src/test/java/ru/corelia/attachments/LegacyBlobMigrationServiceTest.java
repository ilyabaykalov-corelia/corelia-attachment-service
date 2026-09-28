package ru.corelia.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.corelia.support.Json.object;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.LegacyAttachmentEnumerator;
import ru.corelia.provider.LegacyAttachmentReferenceUpdater;
import ru.corelia.provider.PermissionProvider;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.BinaryLocation;
import ru.corelia.provider.model.BinaryMetadata;
import ru.corelia.provider.model.BinaryStoreRequest;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.provider.model.StoredFile;

class LegacyBlobMigrationServiceTest {
    @Test void rejectsMigrationOutsideMaintenanceMode() {
        CoreliaConfig config = mock(CoreliaConfig.class);
        when(config.value("LEGACY_BLOB_MIGRATION_MODE")).thenReturn("false");
        LegacyAttachmentEnumerator source = auth -> { throw new AssertionError("Источник не должен вызываться"); };
        PermissionProvider permissions = (permission, auth) -> { throw new AssertionError("Права не должны проверяться"); };
        var service = new LegacyBlobMigrationService(
                mock(BinaryStorage.class), mock(BlobRegistry.class), List.of(source), List.of(),
                new LegacyBlobMigrationMode(config), permissions);

        assertEquals(409, assertThrows(ApiException.class, () -> service.migrateAll(auth())).status());
    }

    @Test void copiesLegacyContentVerifiesChecksumAndReplacesReference() {
        byte[] content = "historical".getBytes(StandardCharsets.UTF_8);
        String checksum = "f5dbf9fe930c4f499bc6573d86f0156f86ed10363bfee0a71efb3eacce58410f";
        AttachmentMetadata attachment = new AttachmentMetadata(
                "attachment-1", "attachment-1", "document-1", "old.txt", "text/plain", content.length,
                1, true, Instant.EPOCH, new StorageReference("platform-v-dam:old"));
        var database = new DriverManagerDataSource("jdbc:h2:mem:migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcClient jdbc = JdbcClient.create(database);
        jdbc.sql("""
                create table blob (
                    id uuid primary key, storage_provider varchar(50), bucket varchar(255), object_key varchar(255),
                    sha256 varchar(64), size bigint, media_type varchar(255), state varchar(32), created_at timestamp,
                    created_by varchar(255), committed_at timestamp, deleted_at timestamp)
                """).update();
        Storage storage = new Storage(content, checksum);
        LegacyAttachmentEnumerator source = auth -> List.of(attachment);
        ReferenceUpdater updater = new ReferenceUpdater();
        CoreliaConfig config = mock(CoreliaConfig.class);
        when(config.value("LEGACY_BLOB_MIGRATION_MODE")).thenReturn("true");
        PermissionProvider permissions = (permission, auth) -> assertEquals("Attachment:migrate", permission);
        var service = new LegacyBlobMigrationService(
                storage, new BlobRegistry(jdbc), List.of(source), List.of(updater),
                new LegacyBlobMigrationMode(config), permissions);

        assertEquals(1, service.migrateAll(auth()).migrated());
        assertEquals("platform-v-dam:old", updater.expected.value());
        assertTrue(updater.replacement.value().startsWith("corelia-blob://"));
        assertEquals("COMMITTED", jdbc.sql("select state from blob").query(String.class).single());
    }

    private static AuthContext auth() {
        return new AuthContext("Bearer test", "id", "operator", "Operator", "", List.of("app_owner"), "operator");
    }

    private static final class ReferenceUpdater implements LegacyAttachmentReferenceUpdater {
        private StorageReference expected;
        private StorageReference replacement;

        @Override public void replaceStorageReference(
                AttachmentMetadata attachment, StorageReference expectedReference, StorageReference replacementReference, AuthContext auth) {
            expected = expectedReference;
            replacement = replacementReference;
        }
    }

    private static final class Storage implements BinaryStorage {
        private final byte[] content;
        private final String checksum;
        private BinaryLocation location;

        private Storage(byte[] content, String checksum) {
            this.content = content;
            this.checksum = checksum;
        }

        @Override public BinaryLocation reserve(BinaryStoreRequest request, AuthContext auth) {
            location = new BinaryLocation(new StorageReference("corelia-blob://" + UUID.randomUUID()), "s3", "bucket", "blobs/test");
            return location;
        }

        @Override public InputStream read(StorageReference reference, AuthContext auth) {
            return new ByteArrayInputStream(content);
        }

        @Override public StoredFile store(BinaryStoreRequest request, InputStream body, AuthContext auth) {
            return new StoredFile(location.reference(), checksum, content.length, "text/plain");
        }

        @Override public BinaryMetadata metadata(StorageReference reference, AuthContext auth) {
            return new BinaryMetadata(content.length, "text/plain", checksum);
        }
    }
}
