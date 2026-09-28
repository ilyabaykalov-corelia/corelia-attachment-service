# Документация attachment-service

Spring Boot сервис отвечает за загрузку, замену, чтение, удаление и версии файлов; метаданные и состав версии документа согласуются через SPI и document-service. API имеет внутренний префикс `/internal/v1`; gateway — внешний адаптер. Сборка и тесты: Maven reactor. См. [Corelia docs](../../docs/README.md).
