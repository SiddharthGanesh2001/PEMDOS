package com.pemdos.coordinator.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Entity
@Table(name = "stored_objects")
public class StoredObject {

    @Id
    private String objectId;

    @Column(unique = true, nullable = false)
    private String objectKey;

    private long originalSize;
    private long shardSize;
    private String contentType;
    private String combinedFingerprint;

    // Random GF(2^8) point at which all shard polynomials are evaluated.
    // Stored per object so the coordinator can re-verify any shard later.
    private int homomorphicEvalPoint;

    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "objectId", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShardMetadata> shards;
}
