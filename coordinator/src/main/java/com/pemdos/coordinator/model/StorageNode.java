package com.pemdos.coordinator.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "storage_nodes")
public class StorageNode {

    public enum Status { HEALTHY, UNHEALTHY, UNKNOWN }

    @Id
    private String nodeId;

    private String host;
    private int port;

    @Enumerated(EnumType.STRING)
    private Status status = Status.UNKNOWN;

    private Instant lastHeartbeat;
    private long totalShards;
    private long totalBytes;

    public String getGrpcTarget() {
        return host + ":" + port;
    }
}
