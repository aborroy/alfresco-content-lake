package org.hyland.alfresco.contentlake.repo.acl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.TransactionListenerAdapter;
import org.alfresco.util.transaction.TransactionSupportUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class AclChangeTxnBuffer {

    private static final String REQUESTS_RESOURCE_KEY = AclChangeTxnBuffer.class.getName() + ".requests";
    private static final String LISTENER_BOUND_RESOURCE_KEY = AclChangeTxnBuffer.class.getName() + ".listenerBound";

    private final AclChangePublisher publisher;

    void enqueueAfterCommit(AclChangeRequest request) {
        if (!TransactionSupportUtil.isActualTransactionActive()) {
            publishSafely(request);
            return;
        }

        Map<String, AclChangeRequest> requests = getOrCreateRequestMap();
        requests.merge(request.nodeId(), request, AclChangeRequest::merge);

        if (TransactionSupportUtil.getResource(LISTENER_BOUND_RESOURCE_KEY) == null) {
            TransactionSupportUtil.bindResource(LISTENER_BOUND_RESOURCE_KEY, Boolean.TRUE);
            AlfrescoTransactionSupport.bindListener(new TransactionListenerAdapter() {
                @Override
                public void afterCommit() {
                    requests.values().forEach(AclChangeTxnBuffer.this::publishSafely);
                }
            });
        }
    }

    private void publishSafely(AclChangeRequest request) {
        try {
            publisher.publish(request);
        } catch (RuntimeException e) {
            log.error("Failed to publish ACL change notification for node {}", request.nodeId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, AclChangeRequest> getOrCreateRequestMap() {
        Map<String, AclChangeRequest> requests =
                (Map<String, AclChangeRequest>) TransactionSupportUtil.getResource(REQUESTS_RESOURCE_KEY);
        if (requests != null) {
            return requests;
        }
        requests = new LinkedHashMap<>();
        TransactionSupportUtil.bindResource(REQUESTS_RESOURCE_KEY, requests);
        return requests;
    }
}
