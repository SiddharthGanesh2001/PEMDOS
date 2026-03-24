package com.pemdos.node.grpc;

import com.pemdos.common.proto.*;
import com.pemdos.node.service.ShardStorageService;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class StorageNodeGrpcService extends StorageNodeServiceGrpc.StorageNodeServiceImplBase {

    private final ShardStorageService shardStorageService;
    private final String nodeId;
    private final long startTime = Instant.now().getEpochSecond();

    @Override
    public void storeShard(StoreShardRequest request, StreamObserver<StoreShardResponse> responseObserver) {
        try {
            boolean success = shardStorageService.storeShard(
                    request.getObjectId(),
                    request.getShardIndex(),
                    request.getData().toByteArray(),
                    request.getSha256Hash()
            );

            StoreShardResponse response = StoreShardResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Stored" : "Hash verification failed")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error storing shard", e);
            responseObserver.onNext(StoreShardResponse.newBuilder()
                    .setSuccess(false).setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getShard(GetShardRequest request, StreamObserver<GetShardResponse> responseObserver) {
        try {
            Optional<byte[]> data = shardStorageService.getShard(
                    request.getObjectId(),
                    request.getShardIndex()
            );

            GetShardResponse response = data.map(bytes -> GetShardResponse.newBuilder()
                            .setFound(true)
                            .setData(ByteString.copyFrom(bytes))
                            .build())
                    .orElse(GetShardResponse.newBuilder().setFound(false).build());

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error getting shard", e);
            responseObserver.onNext(GetShardResponse.newBuilder().setFound(false)
                    .setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deleteShard(DeleteShardRequest request, StreamObserver<DeleteShardResponse> responseObserver) {
        try {
            boolean deleted = shardStorageService.deleteShard(
                    request.getObjectId(),
                    request.getShardIndex()
            );

            responseObserver.onNext(DeleteShardResponse.newBuilder()
                    .setSuccess(deleted).setMessage(deleted ? "Deleted" : "Not found").build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error deleting shard", e);
            responseObserver.onNext(DeleteShardResponse.newBuilder()
                    .setSuccess(false).setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void healthCheck(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        long uptime = Instant.now().getEpochSecond() - startTime;
        responseObserver.onNext(HealthCheckResponse.newBuilder()
                .setHealthy(true)
                .setNodeId(nodeId)
                .setUptimeSeconds(uptime)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getNodeStatus(NodeStatusRequest request, StreamObserver<NodeStatusResponse> responseObserver) {
        try {
            long[] stats = shardStorageService.getStats();
            responseObserver.onNext(NodeStatusResponse.newBuilder()
                    .setNodeId(nodeId)
                    .setTotalShards(stats[0])
                    .setTotalBytes(stats[1])
                    .setAvailableBytes(stats[2])
                    .setHealthy(true)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error getting node status", e);
            responseObserver.onNext(NodeStatusResponse.newBuilder()
                    .setNodeId(nodeId).setHealthy(false).build());
            responseObserver.onCompleted();
        }
    }
}
