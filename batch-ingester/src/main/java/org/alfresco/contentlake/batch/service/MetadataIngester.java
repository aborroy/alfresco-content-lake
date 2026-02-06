package org.alfresco.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.config.AppConfig;
import org.alfresco.contentlake.batch.model.TransformationTask;
import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.contentlake.client.HxprDocumentApi;
import org.alfresco.contentlake.client.HxprService;
import org.alfresco.contentlake.model.HxprDocument;
import org.alfresco.core.model.Node;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Maps Alfresco node metadata into hxpr documents and returns a transformation task for content processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataIngester {

    private static final String SYS_FILE = "SysFile";
    private static final String MIXIN_CIN_REMOTE = "CinRemote";

    /**
     * Ingest property keys.
     *
     * <p>Keep these keys stable since they become OpenSearch field names via {@code cin_ingestProperties}.</p>
     */
    private static final String P_ALF_NODE_ID = "alfresco_nodeId";
    private static final String P_ALF_REPO_ID = "alfresco_repositoryId";
    private static final String P_ALF_PATH = "alfresco_path";
    private static final String P_ALF_NAME = "alfresco_name";
    private static final String P_ALF_MIME = "alfresco_mimeType";
    private static final String P_ALF_MODIFIED_AT = "alfresco_modifiedAt";
    private static final String P_ALF_READ_AUTHORITIES = "alfresco_readAuthorities";

    private final AlfrescoClient alfrescoClient;
    private final HxprDocumentApi documentApi;
    private final HxprService hxprService;
    private final AppConfig.HxprProperties hxprProps;

    /**
     * Creates or updates the hxpr document for the given node and returns a transformation task.
     *
     * @param node Alfresco node
     * @return transformation task referencing the node and created or updated hxpr document
     */
    public TransformationTask ingestMetadata(Node node) {
        log.debug("Ingesting metadata for node: {} ({})", node.getName(), node.getId());

        HxprDocument existing = hxprService.findByNodeId(node.getId());
        HxprDocument doc = (existing != null) ? updateDocument(existing, node) : createDocument(node);

        return new TransformationTask(
                node.getId(),
                doc.getSysId(),
                node.getContent() != null ? node.getContent().getMimeType() : null
        );
    }

    private HxprDocument createDocument(Node node) {
        hxprService.ensureFolder(hxprProps.getTargetPath());

        HxprDocument doc = buildDocument(node);
        String parentPath = hxprProps.getTargetPath();

        HxprDocument created = hxprService.createDocument(parentPath, doc);
        log.info("Created hxpr document: {} for node: {}", created.getSysId(), node.getId());
        return created;
    }

    private HxprDocument updateDocument(HxprDocument existing, Node node) {
        HxprDocument doc = buildDocument(node);
        doc.setSysId(existing.getSysId());

        HxprDocument updated = documentApi.updateById(existing.getSysId(), doc);
        log.info("Updated hxpr document: {} for node: {}", updated.getSysId(), node.getId());
        return updated;
    }

    /**
     * Builds a hxpr document payload from the Alfresco node.
     *
     * <p>Includes both {@code cin_*} fields and the flattened {@code alfresco*} fields.</p>
     */
    private HxprDocument buildDocument(Node node) {
        HxprDocument doc = new HxprDocument();

        doc.setSysPrimaryType(SYS_FILE);
        doc.setSysName(node.getId());
        doc.setSysMixinTypes(List.of(MIXIN_CIN_REMOTE));

        doc.setCinId(node.getId());
        doc.setCinSourceId(alfrescoClient.getRepositoryId());
        doc.setCinPaths(buildCinPaths(node));

        List<String> readerList = buildReaderList(node);
        doc.setCinRead(readerList);
        doc.setCinDeny(List.of());

        Map<String, Object> props = buildIngestProperties(node, doc.getCinSourceId(), readerList);
        doc.setCinIngestProperties(props);
        doc.setCinIngestPropertyNames(new ArrayList<>(props.keySet()));

        doc.setSyncStatus(HxprDocument.SyncStatus.PENDING);
        doc.setSyncError(null);

        applyFlattenedAlfrescoFields(doc, node, doc.getCinSourceId(), readerList);

        return doc;
    }

    private List<String> buildReaderList(Node node) {
        Set<String> readers = alfrescoClient.extractReadAuthorities(node);
        return new ArrayList<>(readers);
    }

    private Map<String, Object> buildIngestProperties(Node node, String repositoryId, List<String> readerList) {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put(P_ALF_NODE_ID, node.getId());
        props.put(P_ALF_REPO_ID, repositoryId);
        props.put(P_ALF_NAME, node.getName());
        props.put(P_ALF_PATH, node.getPath() != null ? node.getPath().getName() : null);
        props.put(P_ALF_MIME, node.getContent() != null ? node.getContent().getMimeType() : null);
        props.put(P_ALF_MODIFIED_AT, node.getModifiedAt() != null ? node.getModifiedAt().toString() : null);
        props.put(P_ALF_READ_AUTHORITIES, readerList);

        props.values().removeIf(Objects::isNull);
        return props;
    }

    private void applyFlattenedAlfrescoFields(
            HxprDocument doc,
            Node node,
            String repositoryId,
            List<String> readerList
    ) {
        doc.setAlfrescoNodeId(node.getId());
        doc.setAlfrescoRepositoryId(repositoryId);
        doc.setAlfrescoName(node.getName());
        doc.setAlfrescoPath(node.getPath() != null ? node.getPath().getName() : null);
        doc.setAlfrescoMimeType(node.getContent() != null ? node.getContent().getMimeType() : null);
        doc.setAlfrescoModifiedAt(node.getModifiedAt() != null ? node.getModifiedAt().toString() : null);
        doc.setAlfrescoReadAuthorities(readerList);
    }

    private List<String> buildCinPaths(Node node) {
        if (node.getPath() == null || node.getPath().getName() == null) {
            return List.of();
        }

        String path = node.getPath().getName();
        return path.isBlank() ? List.of() : List.of(path);
    }
}