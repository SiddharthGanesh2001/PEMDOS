package com.pemdos.coordinator.repository;

import com.pemdos.coordinator.model.StoredObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoredObjectRepository extends JpaRepository<StoredObject, String> {

    Optional<StoredObject> findByObjectKey(String objectKey);

    boolean existsByObjectKey(String objectKey);
}
