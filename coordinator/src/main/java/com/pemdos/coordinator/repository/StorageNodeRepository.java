package com.pemdos.coordinator.repository;

import com.pemdos.coordinator.model.StorageNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StorageNodeRepository extends JpaRepository<StorageNode, String> {

    List<StorageNode> findByStatus(StorageNode.Status status);
}
