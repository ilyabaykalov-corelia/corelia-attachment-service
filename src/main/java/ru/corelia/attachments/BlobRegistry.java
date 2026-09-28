package ru.corelia.attachments;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Реестр blob-ов; attachment service является единственным владельцем его состояний. */
@Repository
public class BlobRegistry {
    private final JdbcClient jdbc;

    public BlobRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Blob createPending(
            String storageProvider,
            String bucket,
            String objectKey,
            String sha256,
            long size,
            String mediaType,
            String createdBy) {
        Blob blob = new Blob(
                UUID.randomUUID(), storageProvider, bucket, objectKey, sha256, size, mediaType,
                BlobState.PENDING, Instant.now(), createdBy, null, null);
        jdbc.sql("""
                insert into blob (id, storage_provider, bucket, object_key, sha256, size, media_type, state,
                                  created_at, created_by, committed_at, deleted_at)
                values (:id, :storageProvider, :bucket, :objectKey, :sha256, :size, :mediaType, :state,
                        :createdAt, :createdBy, :committedAt, :deletedAt)
                """)
                .param("id", blob.id())
                .param("storageProvider", blob.storageProvider())
                .param("bucket", blob.bucket())
                .param("objectKey", blob.objectKey())
                .param("sha256", blob.sha256())
                .param("size", blob.size())
                .param("mediaType", blob.mediaType())
                .param("state", blob.state().name())
                .param("createdAt", blob.createdAt())
                .param("createdBy", blob.createdBy())
                .param("committedAt", blob.committedAt())
                .param("deletedAt", blob.deletedAt())
                .update();
        return blob;
    }

    public Optional<Blob> find(UUID id) {
        return jdbc.sql("select * from blob where id = :id")
                .param("id", id)
                .query(Blob.class)
                .optional();
    }

    public void commit(UUID id) {
        transition(id, BlobState.PENDING, BlobState.COMMITTED, "committed_at");
    }

    public void orphan(UUID id) {
        transition(id, BlobState.PENDING, BlobState.ORPHANED, null);
    }

    public void beginDeleting(UUID id) {
        transition(id, BlobState.ORPHANED, BlobState.DELETING, null);
    }

    public void markDeleted(UUID id) {
        transition(id, BlobState.DELETING, BlobState.DELETED, "deleted_at");
    }

    private void transition(UUID id, BlobState from, BlobState to, String timestampColumn) {
        String timestamp = timestampColumn == null ? "" : ", " + timestampColumn + " = :now";
        int updated = jdbc.sql("update blob set state = :to" + timestamp + " where id = :id and state = :from")
                .param("to", to.name())
                .param("id", id)
                .param("from", from.name())
                .param("now", Instant.now())
                .update();
        if (updated != 1) throw new IllegalStateException("Недопустимый переход состояния blob " + id);
    }
}
