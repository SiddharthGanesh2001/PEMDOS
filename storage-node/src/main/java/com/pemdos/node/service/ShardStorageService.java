package com.pemdos.node.service;

import com.pemdos.common.fingerprint.FingerprintService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShardStorageService {

    private final Path storagePath;
    private final FingerprintService fingerprintService;

    public boolean storeShard(String objectId, int shardIndex, byte[] data, String expectedHash) throws IOException {
        if (!fingerprintService.verifyFingerprint(data, expectedHash)) {
            log.warn("Hash mismatch for object={} shard={}", objectId, shardIndex);
            return false;
        }

        Path shardDir = storagePath.resolve(objectId);
        Files.createDirectories(shardDir);

        Path shardFile = shardDir.resolve(shardIndex + ".shard");
        Files.write(shardFile, data);

        log.debug("Stored shard object={} index={} bytes={}", objectId, shardIndex, data.length);
        return true;
    }

    public Optional<byte[]> getShard(String objectId, int shardIndex) throws IOException {
        Path shardFile = storagePath.resolve(objectId).resolve(shardIndex + ".shard");

        if (!Files.exists(shardFile)) {
            return Optional.empty();
        }

        return Optional.of(Files.readAllBytes(shardFile));
    }

    public boolean deleteShard(String objectId, int shardIndex) throws IOException {
        Path shardFile = storagePath.resolve(objectId).resolve(shardIndex + ".shard");
        return Files.deleteIfExists(shardFile);
    }

    public long[] getStats() throws IOException {
        long totalShards = 0;
        long totalBytes = 0;

        if (Files.exists(storagePath)) {
            var entries = Files.walk(storagePath)
                    .filter(p -> p.toString().endsWith(".shard"));
            for (Path shard : (Iterable<Path>) entries::iterator) {
                totalShards++;
                totalBytes += Files.size(shard);
            }
        }

        long availableBytes = storagePath.toFile().getFreeSpace();
        return new long[]{totalShards, totalBytes, availableBytes};
    }
}
