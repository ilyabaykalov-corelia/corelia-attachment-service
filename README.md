# corelia-attachment-service

Владелец физического lifecycle blob: потоковая загрузка/чтение, replacement,
metadata, checksum и очистка orphaned content. Метаданные registry находятся
в `corelia_attachment`; связь вложения с document version остаётся у data
owner и изменяется через document-service.

Сервис публикует внутренние `/internal/v1` endpoint по mTLS. В Compose выбран
S3 provider (SeaweedFS локально), native-data и native-permissions. Ограничение
multipart задаёт `MAX_ATTACHMENT_SIZE_MB`; blob recovery horizon —
`BLOB_GC_RECOVERY_HOURS`.

```bash
mvn -pl corelia-attachment-service -am test
./scripts/up.sh
```

Для S3 provider обязательны endpoint, bucket и credentials. См.
[provider S3](../corelia-provider-s3/README.md) и
[versioning](../docs/document-versioning.md).
