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

    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "objectId", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShardMetadata> shards;
}
