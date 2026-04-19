package com.pemdos.coordinator.config;

import com.pemdos.coordinator.model.StorageNode;
import com.pemdos.coordinator.repository.StorageNodeRepository;
import com.pemdos.coordinator.service.NodeClientService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NodeInitializer {

    private final StorageNodeRepository nodeRepository;
    private final NodeClientService nodeClientService;

    // In Docker each node is reachable by its service name (which matches its nodeId).
    // For local dev, override with e.g. PEMDOS_NODES_HOST=localhost
    @Value("${pemdos.nodes.host:}")
    private String hostOverride;

    @PostConstruct
    public void init() {
        List<NodeConfig> nodes = List.of(
                new NodeConfig("node-1", 5001),
                new NodeConfig("node-2", 5002),
                new NodeConfig("node-3", 5003),
                new NodeConfig("node-4", 5004),
                new NodeConfig("node-5", 5005)
        );

        for (NodeConfig cfg : nodes) {
            // Use explicit override if provided (local dev), otherwise use the node ID as the hostname (Docker)
            String host = hostOverride.isBlank() ? cfg.id : hostOverride;
            if (!nodeRepository.existsById(cfg.id)) {
                StorageNode node = new StorageNode();
                node.setNodeId(cfg.id);
                node.setHost(host);
                node.setPort(cfg.port);
                nodeRepository.save(node);
                log.info("Registered node {} at {}:{}", cfg.id, host, cfg.port);
            }
        }

        updateNodeStatuses();
    }

    private void updateNodeStatuses() {
        for (StorageNode node : nodeRepository.findAll()) {
            boolean healthy = nodeClientService.healthCheck(node).isPresent();
            node.setStatus(healthy ? StorageNode.Status.HEALTHY : StorageNode.Status.UNHEALTHY);
            node.setLastHeartbeat(Instant.now());
            nodeRepository.save(node);
            log.info("Node {} status: {}", node.getNodeId(), node.getStatus());
        }
    }

    private record NodeConfig(String id, int port) {}
}
