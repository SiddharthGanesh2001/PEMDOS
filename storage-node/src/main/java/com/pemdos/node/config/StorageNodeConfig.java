package com.pemdos.node.config;

import com.pemdos.common.fingerprint.FingerprintService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class StorageNodeConfig {

    @Value("${pemdos.node.id}")
    private String nodeId;

    @Value("${pemdos.node.storage-path}")
    private String storagePath;

    @Bean
    public String nodeId() {
        return nodeId;
    }

    @Bean
    public Path storagePath() throws IOException {
        Path path = Path.of(storagePath);
        Files.createDirectories(path);
        return path;
    }

    @Bean
    public FingerprintService fingerprintService() {
        return new FingerprintService();
    }
}
