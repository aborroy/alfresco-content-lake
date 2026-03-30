package org.hyland.alfresco.contentlake.repo.acl;

interface AclChangePublisher {

    void publish(AclChangeRequest request);
}
