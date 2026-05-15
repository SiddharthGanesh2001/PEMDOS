package com.pemdos.coordinator.service;

import com.pemdos.common.codec.ReedSolomonCodec;
import com.pemdos.common.fingerprint.FingerprintService;
import com.pemdos.common.fingerprint.HomomorphicFingerprint;
import com.pemdos.coordinator.model.ShardMetadata;
import com.pemdos.coordinator.model.StorageNode;
import com.pemdos.coordinator.model.StoredObject;
import com.pemdos.coordinator.repository.StorageNodeRepository;
import com.pemdos.coordinator.repository.StoredObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStoreService {

    private final ReedSolomonCodec codec;
    private final FingerprintService fingerprintService;
    private final HomomorphicFingerprint homomorphicFingerprint;
    private final NodeClientService nodeClientService;
    private final StoredObjectRepository objectRepository;
    private final StorageNodeRepository nodeRepository;

    public StoredObject storeObject(String objectKey, byte[] data, String contentType) {
        if (objectRepository.existsByObjectKey(objectKey)) {
            throw new IllegalArgumentException("Object already exists: " + objectKey);
        }

        List<StorageNode> nodes = nodeRepository.findByStatus(StorageNode.Status.HEALTHY);
        if (nodes.size() < 5) {
            throw new IllegalStateException("Need 5 healthy nodes, found: " + nodes.size());
        }

        byte[][] shards = codec.encode(data);
        String[] hashes = fingerprintService.computeShardFingerprints(shards);
        String combinedFingerprint = fingerprintService.computeCombinedFingerprint(hashes);

        // Pick a random GF(2^8) evaluation point and compute homomorphic fingerprints.
        // dataShardArrays holds only the first 3 shards (data); the encoding matrix
        // determines what the parity fingerprints must be.
        int evalPoint = homomorphicFingerprint.randomEvalPoint();
        byte[][] dataShardArrays = new byte[3][];
        for (int i = 0; i < 3; i++) dataShardArrays[i] = shards[i];
        int[] dataFps = homomorphicFingerprint.computeDataFingerprints(dataShardArrays, evalPoint);

        String objectId = UUID.randomUUID().toString();
        List<ShardMetadata> shardMetadataList = new ArrayList<>();

        for (int i = 0; i < shards.length; i++) {
            StorageNode node = nodes.get(i);
            boolean isParity = i >= 3;

            boolean success = nodeClientService.storeShard(node, objectId, i, shards[i], hashes[i], isParity);
            if (!success) {
                throw new RuntimeException("Failed to store shard " + i + " on node " + node.getNodeId());
            }

            ShardMetadata meta = new ShardMetadata();
            meta.setObjectId(objectId);
            meta.setShardIndex(i);
            meta.setNodeId(node.getNodeId());
            meta.setSha256Hash(hashes[i]);
            meta.setParity(isParity);
            meta.setSizeBytes(shards[i].length);
            // Expected fingerprint: for data shards this is just dataFps[i];
            // for parity shards the encoding matrix row gives the linear combination.
            meta.setHomomorphicFingerprint(homomorphicFingerprint.expectedFingerprint(dataFps, i));
            shardMetadataList.add(meta);
        }

        StoredObject storedObject = new StoredObject();
        storedObject.setObjectId(objectId);
        storedObject.setObjectKey(objectKey);
        storedObject.setOriginalSize(data.length);
        storedObject.setShardSize(shards[0].length);
        storedObject.setContentType(contentType);
        storedObject.setCombinedFingerprint(combinedFingerprint);
        storedObject.setHomomorphicEvalPoint(evalPoint);
        storedObject.setShards(shardMetadataList);

        return objectRepository.save(storedObject);
    }

    public byte[] retrieveObject(String objectKey) {
        StoredObject storedObject = objectRepository.findByObjectKey(objectKey)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectKey));

        byte[][] shards = new byte[5][];
        boolean[] shardPresent = new boolean[5];

        for (ShardMetadata meta : storedObject.getShards()) {
            StorageNode node = nodeRepository.findById(meta.getNodeId()).orElse(null);
            if (node == null) continue;

            Optional<byte[]> shardData = nodeClientService.getShard(node, storedObject.getObjectId(), meta.getShardIndex());
            if (shardData.isEmpty()) continue;

            // Layer 1: SHA-256 integrity check - detects bit-level corruption
            if (!fingerprintService.verifyFingerprint(shardData.get(), meta.getSha256Hash())) {
                log.warn("SHA-256 mismatch for shard {} of object {}", meta.getShardIndex(), objectKey);
                continue;
            }

            // Layer 2: Homomorphic consistency check - verifies the shard is consistent
            // with the original Reed-Solomon encoding, not merely internally intact.
            int actualFp = HomomorphicFingerprint.evaluate(shardData.get(), storedObject.getHomomorphicEvalPoint());
            if (actualFp != meta.getHomomorphicFingerprint()) {
                log.warn("Homomorphic fingerprint mismatch for shard {} of object {}", meta.getShardIndex(), objectKey);
                continue;
            }

            shards[meta.getShardIndex()] = shardData.get();
            shardPresent[meta.getShardIndex()] = true;
        }

        if (!codec.canReconstruct(shardPresent)) {
            throw new IllegalStateException("Not enough shards available to reconstruct: " + objectKey);
        }

        return codec.decode(shards, shardPresent, (int) storedObject.getOriginalSize());
    }

    public void deleteObject(String objectKey) {
        StoredObject storedObject = objectRepository.findByObjectKey(objectKey)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectKey));

        for (ShardMetadata meta : storedObject.getShards()) {
            StorageNode node = nodeRepository.findById(meta.getNodeId()).orElse(null);
            if (node != null) {
                nodeClientService.deleteShard(node, storedObject.getObjectId(), meta.getShardIndex());
            }
        }

        objectRepository.delete(storedObject);
    }

    public List<StoredObject> listObjects() {
        return objectRepository.findAll();
    }

    // Fetches every shard and runs both checks independently, returning a report
    // regardless of whether the object is reconstructable.
    public List<ShardVerificationResult> verifyObject(String objectKey) {
        StoredObject storedObject = objectRepository.findByObjectKey(objectKey)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectKey));

        List<ShardVerificationResult> results = new ArrayList<>();

        for (ShardMetadata meta : storedObject.getShards()) {
            StorageNode node = nodeRepository.findById(meta.getNodeId()).orElse(null);

            if (node == null) {
                results.add(new ShardVerificationResult(meta.getShardIndex(), meta.getNodeId(),
                        "UNREACHABLE", "UNREACHABLE", "UNREACHABLE"));
                continue;
            }

            Optional<byte[]> shardData = nodeClientService.getShard(node, storedObject.getObjectId(), meta.getShardIndex());

            if (shardData.isEmpty()) {
                results.add(new ShardVerificationResult(meta.getShardIndex(), meta.getNodeId(),
                        "UNREACHABLE", "UNREACHABLE", "UNREACHABLE"));
                continue;
            }

            String sha256Result = fingerprintService.verifyFingerprint(shardData.get(), meta.getSha256Hash())
                    ? "PASS" : "FAIL";

            int actualFp = HomomorphicFingerprint.evaluate(shardData.get(), storedObject.getHomomorphicEvalPoint());
            String homomorphicResult = (actualFp == meta.getHomomorphicFingerprint()) ? "PASS" : "FAIL";

            results.add(new ShardVerificationResult(
                    meta.getShardIndex(), meta.getNodeId(), "REACHABLE", sha256Result, homomorphicResult));
        }

        return results;
    }

    public record ShardVerificationResult(
            int shardIndex,
            String nodeId,
            String nodeStatus,
            String sha256,
            String homomorphic
    ) {}
}
