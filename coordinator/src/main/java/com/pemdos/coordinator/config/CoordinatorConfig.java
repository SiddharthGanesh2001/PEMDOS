package com.pemdos.coordinator.config;

import com.pemdos.common.codec.ReedSolomonCodec;
import com.pemdos.common.fingerprint.FingerprintService;
import com.pemdos.common.fingerprint.HomomorphicFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoordinatorConfig {

    @Value("${pemdos.codec.data-shards}")
    private int dataShards;

    @Value("${pemdos.codec.parity-shards}")
    private int parityShards;

    @Bean
    public ReedSolomonCodec reedSolomonCodec() {
        return new ReedSolomonCodec(dataShards, parityShards);
    }

    @Bean
    public FingerprintService fingerprintService() {
        return new FingerprintService();
    }

    @Bean
    public HomomorphicFingerprint homomorphicFingerprint() {
        return new HomomorphicFingerprint(dataShards, parityShards);
    }
}
