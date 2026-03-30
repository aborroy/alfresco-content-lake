package org.hyland.alfresco.contentlake.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PermissionSyncQueueListenerTest {

    @Mock
    private PermissionSyncService permissionSyncService;

    private PermissionSyncQueueListener listener;

    @BeforeEach
    void setUp() {
        listener = new PermissionSyncQueueListener(new ObjectMapper(), permissionSyncService);
    }

    @Test
    void onMessage_invokesPermissionReconciliation() {
        listener.onMessage("""
                {
                  "eventType":"contentlake.acl.changed",
                  "sourceType":"alfresco",
                  "nodeId":"node-1",
                  "recursive":true,
                  "reason":"inherit-permissions-disabled",
                  "occurredAt":"2026-03-30T12:00:00Z"
                }
                """);

        ArgumentCaptor<PermissionSyncRequest> request = ArgumentCaptor.forClass(PermissionSyncRequest.class);
        verify(permissionSyncService).syncPermissions(request.capture());
        assertThat(request.getValue().getNodeIds()).containsExactly("node-1");
        assertThat(request.getValue().isRecursive()).isTrue();
    }

    @Test
    void onMessage_rejectsUnsupportedEventType() {
        assertThatThrownBy(() -> listener.onMessage("""
                {
                  "eventType":"wrong.event",
                  "sourceType":"alfresco",
                  "nodeId":"node-1",
                  "recursive":false
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
    }
}
