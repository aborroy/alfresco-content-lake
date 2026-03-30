package org.hyland.alfresco.contentlake.repo.acl;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
class AclChangeJmsPublisher implements AclChangePublisher {

    private final boolean enabled;
    private final String queueName;
    private final JmsTemplate jmsTemplate;

    AclChangeJmsPublisher(
            @Value("${content.lake.permission.sync.enabled:false}") boolean enabled,
            @Value("${content.lake.permission.sync.queue-name:contentlake.acl.changed}") String queueName,
            JmsTemplate contentLakePermissionSyncJmsTemplate
    ) {
        this.enabled = enabled;
        this.queueName = queueName;
        this.jmsTemplate = contentLakePermissionSyncJmsTemplate;
    }

    @Override
    public void publish(AclChangeRequest request) {
        if (!enabled) {
            return;
        }
        String payload = payloadFor(request, Instant.now());
        jmsTemplate.send(queueName, session -> {
            TextMessage message = session.createTextMessage(payload);
            setStringProperty(message, "eventType", "contentlake.acl.changed");
            setStringProperty(message, "sourceType", "alfresco");
            setStringProperty(message, "nodeId", request.nodeId());
            message.setBooleanProperty("recursive", request.recursive());
            return message;
        });
        log.debug("Published ACL reconciliation message for node {} recursive={}", request.nodeId(), request.recursive());
    }

    static String payloadFor(AclChangeRequest request, Instant occurredAt) {
        return "{"
                + "\"eventType\":\"contentlake.acl.changed\","
                + "\"sourceType\":\"alfresco\","
                + "\"nodeId\":\"" + escapeJson(request.nodeId()) + "\","
                + "\"recursive\":" + request.recursive() + ","
                + "\"reason\":\"" + escapeJson(request.reason()) + "\","
                + "\"occurredAt\":\"" + occurredAt + "\""
                + "}";
    }

    private static void setStringProperty(TextMessage message, String key, String value) throws JMSException {
        message.setStringProperty(key, value);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
