package org.hyland.alfresco.contentlake.batch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncMessage;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionSyncQueueListener {

    private static final String EVENT_TYPE = "contentlake.acl.changed";
    private static final String SOURCE_TYPE = "alfresco";

    private final ObjectMapper objectMapper;
    private final PermissionSyncService permissionSyncService;

    @Value("${content.lake.permission.sync.queue-name:contentlake.acl.changed}")
    private String queueName;

    @JmsListener(destination = "${content.lake.permission.sync.queue-name:contentlake.acl.changed}")
    public void onMessage(String payload) {
        PermissionSyncMessage message = parse(payload);
        validate(message);

        PermissionSyncRequest request = new PermissionSyncRequest();
        request.setNodeIds(List.of(message.nodeId()));
        request.setRecursive(message.recursive());

        permissionSyncService.syncPermissions(request);
        log.debug("Processed ACL reconciliation message from queue {} for node {} recursive={}",
                queueName, message.nodeId(), message.recursive());
    }

    private PermissionSyncMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, PermissionSyncMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid ACL reconciliation message payload", e);
        }
    }

    private static void validate(PermissionSyncMessage message) {
        if (message == null || !EVENT_TYPE.equals(message.eventType())) {
            throw new IllegalArgumentException("Unsupported ACL reconciliation event type");
        }
        if (!SOURCE_TYPE.equals(message.sourceType())) {
            throw new IllegalArgumentException("Unsupported ACL reconciliation source type");
        }
        if (message.nodeId() == null || message.nodeId().isBlank()) {
            throw new IllegalArgumentException("ACL reconciliation message nodeId must not be blank");
        }
    }
}
