package com.aicallcrm.crm.settings;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The company logo (single row), kept in the database so an installation is just the app plus its database. */
@Entity
@Table(name = "app_logo")
class AppLogo {

    static final int ID = 1;

    @Id
    private Integer id = ID;

    private String contentType;
    private byte[] data;
    private Instant updatedAt;

    protected AppLogo() {
    }

    AppLogo(String contentType, byte[] data) {
        replace(contentType, data);
    }

    void replace(String contentType, byte[] data) {
        this.contentType = contentType;
        this.data = data;
        this.updatedAt = Instant.now();
    }

    String getContentType() { return contentType; }
    byte[] getData() { return data; }
}

interface AppLogoRepository extends JpaRepository<AppLogo, Integer> {
}
