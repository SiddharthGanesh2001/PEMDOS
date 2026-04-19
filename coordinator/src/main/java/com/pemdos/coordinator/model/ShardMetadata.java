package com.pemdos.coordinator.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "shard_metadata")
public class ShardMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long shardId;

    private String objectId;
    private int shardIndex;
    private String nodeId;
    private String sha256Hash;
    private boolean isParity;
    private long sizeBytes;
}
