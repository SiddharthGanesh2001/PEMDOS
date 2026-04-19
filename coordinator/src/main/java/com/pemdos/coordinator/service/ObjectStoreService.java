package com.pemdos.coordinator.service;

import com.pemdos.common.codec.ReedSolomonCodec;
import com.pemdos.common.fingerprint.FingerprintService;
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

        // Encode into 3 data + 2 parity shards
        byte[][] shards = codec.encode(data);
        String[] hashes = fingerprintService.computeShardFingerprints(shards);
        String combinedFingerprint = fingerprintService.computeCombinedFingerprint(hashes);

        String objectId = UUID.randomUUID().toString();
        List<ShardMetadata> shardMetadataList = new ArrayList<>();

        // Distribute shard i to node i
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
            shardMetadataList.add(meta);
        }

        StoredObject storedObject = new StoredObject();
        storedObject.setObjectId(objectId);
        storedObject.setObjectKey(objectKey);
        storedObject.setOriginalSize(data.length);
        storedObject.setShardSize(shards[0].length);
        storedObject.setContentType(contentType);
        storedObject.setCombinedFingerprint(combinedFingerprint);
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

            // Verify integrity before accepting
            if (!fingerprintService.verifyFingerprint(shardData.get(), meta.getSha256Hash())) {
                log.warn("Hash mismatch for shard {} of object {}", meta.getShardIndex(), objectKey);
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
}
