package org.hyland.alfresco.contentlake.repo.acl;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;

@Configuration
class AclChangeJmsConfig {

    @Bean
    ConnectionFactory contentLakePermissionSyncConnectionFactory(
            @Value("${content.lake.permission.sync.broker-url:tcp://localhost:61616}") String brokerUrl,
            @Value("${content.lake.permission.sync.broker-user:admin}") String username,
            @Value("${content.lake.permission.sync.broker-password:admin}") String password
    ) {
        return new ActiveMQConnectionFactory(username, password, brokerUrl);
    }

    @Bean
    JmsTemplate contentLakePermissionSyncJmsTemplate(ConnectionFactory contentLakePermissionSyncConnectionFactory) {
        JmsTemplate jmsTemplate = new JmsTemplate(contentLakePermissionSyncConnectionFactory);
        jmsTemplate.setPubSubDomain(false);
        jmsTemplate.setExplicitQosEnabled(true);
        jmsTemplate.setDeliveryPersistent(true);
        return jmsTemplate;
    }
}
