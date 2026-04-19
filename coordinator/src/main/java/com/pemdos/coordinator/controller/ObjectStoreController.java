package com.pemdos.coordinator.controller;

import com.pemdos.coordinator.model.StoredObject;
import com.pemdos.coordinator.service.ObjectStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/objects")
@RequiredArgsConstructor
@Tag(name = "Object Store", description = "Store, retrieve, and delete objects")
public class ObjectStoreController {

    private final ObjectStoreService objectStoreService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an object")
    public ResponseEntity<StoredObject> store(
            @RequestParam String key,
            @RequestPart MultipartFile file) throws IOException {

        StoredObject result = objectStoreService.storeObject(
                key,
                file.getBytes(),
                file.getContentType()
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{objectKey}")
    @Operation(summary = "Download an object")
    public ResponseEntity<byte[]> retrieve(@PathVariable String objectKey) {
        byte[] data = objectStoreService.retrieveObject(objectKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + objectKey + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @GetMapping("/{objectKey}/metadata")
    @Operation(summary = "Get object metadata")
    public ResponseEntity<StoredObject> metadata(@PathVariable String objectKey) {
        return objectStoreService.listObjects().stream()
                .filter(o -> o.getObjectKey().equals(objectKey))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{objectKey}")
    @Operation(summary = "Delete an object")
    public ResponseEntity<Void> delete(@PathVariable String objectKey) {
        objectStoreService.deleteObject(objectKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "List all objects")
    public ResponseEntity<List<StoredObject>> list() {
        return ResponseEntity.ok(objectStoreService.listObjects());
    }
}
