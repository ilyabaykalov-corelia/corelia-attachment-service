package ru.corelia.attachments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.corelia.config.LocalEnvironment;

/** Запускает приложение corelia-attachment-service. */
@SpringBootApplication(
        scanBasePackages = {
            "ru.corelia.config",
            "ru.corelia.support",
            "ru.corelia.auth",
            "ru.corelia.http",
            "ru.corelia.cache",
            "ru.corelia.integration",
            "ru.corelia.transport",
            "ru.corelia.observability",
            "ru.corelia.attachments"
        })
public class AttachmentApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(AttachmentApplication.class);
        app.setDefaultProperties(LocalEnvironment.load());
        app.run(args);
    }
}
