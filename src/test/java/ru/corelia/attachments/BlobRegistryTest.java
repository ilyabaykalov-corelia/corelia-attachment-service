package ru.corelia.attachments;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import ru.corelia.provider.model.BinaryLocation;
import ru.corelia.provider.model.StorageReference;

class BlobRegistryTest {
    @Test
    void allowsOnlyDeclaredLifecycleTransitions() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:blob-registry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                create table blob (
                    id uuid primary key, storage_provider varchar(64), bucket varchar(255), object_key varchar(1024),
                    sha256 varchar(64), size bigint, media_type varchar(255), state varchar(32), created_at timestamp with time zone,
                    created_by varchar(255), committed_at timestamp with time zone, deleted_at timestamp with time zone)
                """).update();
        BlobRegistry registry = new BlobRegistry(jdbc);

        Blob pending = registry.createPending(location(), "a".repeat(64), 12,
                "text/plain", "user-1");
        registry.commit(pending.id());

        assertEquals(BlobState.COMMITTED, registry.find(pending.id()).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> registry.orphan(pending.id()));
    }

    @Test
    void recoversOrphanThroughDeletionLifecycle() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:blob-registry-gc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                create table blob (
                    id uuid primary key, storage_provider varchar(64), bucket varchar(255), object_key varchar(1024),
                    sha256 varchar(64), size bigint, media_type varchar(255), state varchar(32), created_at timestamp with time zone,
                    created_by varchar(255), committed_at timestamp with time zone, deleted_at timestamp with time zone)
                """).update();
        BlobRegistry registry = new BlobRegistry(jdbc);

        Blob pending = registry.createPending(location(), "b".repeat(64), 12,
                "text/plain", "user-1");
        registry.orphan(pending.id());
        registry.beginDeleting(pending.id());
        registry.markDeleted(pending.id());

        assertEquals(BlobState.DELETED, registry.find(pending.id()).orElseThrow().state());
    }

    private static BinaryLocation location() {
        java.util.UUID id = java.util.UUID.randomUUID();
        return new BinaryLocation(new StorageReference("corelia-blob://" + id), "s3", "attachments", "blobs/" + id);
    }
}
