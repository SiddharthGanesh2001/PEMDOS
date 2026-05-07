package com.pemdos.coordinator.service;

import com.pemdos.common.proto.*;
import com.pemdos.coordinator.model.StorageNode;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NodeClientService {

    // One persistent gRPC channel per node, keyed by nodeId
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    private StorageNodeServiceGrpc.StorageNodeServiceBlockingStub stubFor(StorageNode node) {
        ManagedChannel channel = channels.computeIfAbsent(node.getNodeId(), id ->
                ManagedChannelBuilder.forTarget(node.getGrpcTarget())
                        .usePlaintext()
                        .build()
        );
        return StorageNodeServiceGrpc.newBlockingStub(channel);
    }

    public boolean storeShard(StorageNode node, String objectId, int shardIndex,
                              byte[] data, String hash, boolean isParity) {
        try {
            StoreShardRequest request = StoreShardRequest.newBuilder()
                    .setObjectId(objectId)
                    .setShardIndex(shardIndex)
                    .setData(ByteString.copyFrom(data))
                    .setSha256Hash(hash)
                    .setIsParity(isParity)
                    .build();

            StoreShardResponse response = stubFor(node).storeShard(request);
            return response.getSuccess();
        } catch (Exception e) {
            log.error("Failed to store shard on node {}: {}", node.getNodeId(), e.getMessage());
            return false;
        }
    }

    public Optional<byte[]> getShard(StorageNode node, String objectId, int shardIndex) {
        try {
            GetShardRequest request = GetShardRequest.newBuilder()
                    .setObjectId(objectId)
                    .setShardIndex(shardIndex)
                    .build();

            GetShardResponse response = stubFor(node)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .getShard(request);
            if (response.getFound()) {
                return Optional.of(response.getData().toByteArray());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to get shard from node {}: {}", node.getNodeId(), e.getMessage());
            return Optional.empty();
        }
    }

    public boolean deleteShard(StorageNode node, String objectId, int shardIndex) {
        try {
            DeleteShardRequest request = DeleteShardRequest.newBuilder()
                    .setObjectId(objectId)
                    .setShardIndex(shardIndex)
                    .build();

            return stubFor(node)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .deleteShard(request).getSuccess();
        } catch (Exception e) {
            log.warn("Failed to delete shard on node {}: {}", node.getNodeId(), e.getMessage());
            return false;
        }
    }

    public Optional<HealthCheckResponse> healthCheck(StorageNode node) {
        try {
            HealthCheckResponse response = stubFor(node)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .healthCheck(HealthCheckRequest.newBuilder().build());
            return Optional.of(response);
        } catch (Exception e) {
            log.warn("Health check failed for node {}: {}", node.getNodeId(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<NodeStatusResponse> getNodeStatus(StorageNode node) {
        try {
            NodeStatusResponse response = stubFor(node)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .getNodeStatus(NodeStatusRequest.newBuilder().build());
            return Optional.of(response);
        } catch (Exception e) {
            log.warn("Failed to get status from node {}: {}", node.getNodeId(), e.getMessage());
            return Optional.empty();
        }
    }

    @PreDestroy
    public void shutdown() {
        channels.values().forEach(ManagedChannel::shutdown);
    }
}
