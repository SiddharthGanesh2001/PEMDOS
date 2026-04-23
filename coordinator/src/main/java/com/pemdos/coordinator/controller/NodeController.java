package com.pemdos.coordinator.controller;

import com.pemdos.common.proto.NodeStatusResponse;
import com.pemdos.coordinator.model.StorageNode;
import com.pemdos.coordinator.repository.StorageNodeRepository;
import com.pemdos.coordinator.service.NodeClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
@Tag(name = "Nodes", description = "Live status for all storage nodes")
public class NodeController {

    private final StorageNodeRepository nodeRepository;
    private final NodeClientService nodeClientService;

    @GetMapping
    @Operation(summary = "Get live status of all storage nodes")
    public ResponseEntity<List<NodeStatus>> getAllNodes() {
        List<NodeStatus> statuses = nodeRepository.findAll().stream()
                .map(this::buildStatus)
                .toList();
        return ResponseEntity.ok(statuses);
    }

    private NodeStatus buildStatus(StorageNode node) {
        Optional<NodeStatusResponse> live = nodeClientService.getNodeStatus(node);
        // Use the live gRPC result as the source of truth: if the call succeeds
        // the node is reachable right now; if it fails (container down, network error)
        // report UNREACHABLE regardless of what the DB says.
        String liveStatus = live.isPresent() ? "HEALTHY" : "UNREACHABLE";
        return new NodeStatus(
                node.getNodeId(),
                node.getHost(),
                node.getPort(),
                liveStatus,
                live.map(NodeStatusResponse::getTotalShards).orElse(0L),
                live.map(NodeStatusResponse::getTotalBytes).orElse(0L),
                live.map(NodeStatusResponse::getAvailableBytes).orElse(0L)
        );
    }

    public record NodeStatus(
            String nodeId,
            String host,
            int port,
            String status,
            long totalShards,
            long totalBytes,
            long availableBytes
    ) {}
}
