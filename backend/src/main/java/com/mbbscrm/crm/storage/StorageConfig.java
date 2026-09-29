package com.mbbscrm.crm.storage;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the document store: Cloudflare R2 when R2_ENDPOINT / R2_BUCKET / keys are set, otherwise a local
 * folder (development only).
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    FileStorage fileStorage(@Value("${app.storage.local-dir:data/uploads}") String localDir,
                            @Value("${app.storage.r2.endpoint:}") String endpoint,
                            @Value("${app.storage.r2.bucket:}") String bucket,
                            @Value("${app.storage.r2.access-key-id:}") String keyId,
                            @Value("${app.storage.r2.secret-access-key:}") String secret) {
        FileStorage storage;
        if (!endpoint.isBlank() && !bucket.isBlank() && !keyId.isBlank() && !secret.isBlank()) {
            storage = new R2FileStorage(endpoint, bucket, keyId, secret);
        } else {
            storage = new LocalFileStorage(Path.of(localDir));
            log.warn("Documents are stored on local disk. Configure R2_* settings before going to production.");
        }
        log.info("Document storage: {}", storage.describe());
        return storage;
    }
}
