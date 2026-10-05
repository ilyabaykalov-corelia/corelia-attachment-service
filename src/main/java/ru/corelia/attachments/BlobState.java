package ru.corelia.attachments;

/** Состояния физического содержимого, принадлежащего сервису вложений. */
public enum BlobState {
    PENDING,
    COMMITTED,
    ORPHANED,
    DELETING,
    DELETED,
    PENDING_SCAN,
    QUARANTINED
}
