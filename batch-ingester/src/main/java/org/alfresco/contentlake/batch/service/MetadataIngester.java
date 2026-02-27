package org.alfresco.contentlake.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.contentlake.batch.config.AppConfig;
import org.alfresco.contentlake.batch.model.TransformationTask;
import org.alfresco.contentlake.client.AlfrescoClient;
import org.alfresco.contentlake.client.HxprDocumentApi;
import org.alfresco.contentlake.client.HxprService;
import org.alfresco.contentlake.hxpr.api.model.ACE;
import org.alfresco.contentlake.hxpr.api.model.Group;
import org.alfresco.contentlake.hxpr.api.model.User;
import org.alfresco.contentlake.model.HxprDocument;
import org.alfresco.core.model.Node;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataIngester {

    private static final String SYS_FILE = "SysFile";
    private static final String MIXIN_CIN_REMOTE = "CinRemote";

    private static final String P_ALF_NODE_ID = "alfresco_nodeId";
    private static final String P_ALF_REPO_ID = "alfresco_repositoryId";
    private static final String P_ALF_PATH = "alfresco_path";
    private static final String P_ALF_NAME = "alfresco_name";
    private static final String P_ALF_MIME = "alfresco_mimeType";
    private static final String P_ALF_MODIFIED_AT = "alfresco_modifiedAt";

    private static final String EVERYONE_PRINCIPAL = "__Everyone__";
    private static final String GROUP_PREFIX = "GROUP_";
    private static final String PERMISSION_READ = "Read";

    private final AlfrescoClient alfrescoClient;
    private final HxprDocumentApi documentApi;
    private final HxprService hxprService;
    private final AppConfig.HxprProperties hxprProps;

    public TransformationTask ingestMetadata(Node node) {
        log.debug("Ingesting metadata for node: {} ({})", node.getName(), node.getId());

        HxprDocument existing = hxprService.findByNodeId(node.getId());
        HxprDocument doc = (existing != null) ? updateDocument(existing, node) : createDocument(node);

        String documentPath = (node.getPath() != null) ? node.getPath().getName() : null;

        return new TransformationTask(
                node.getId(),
                doc.getSysId(),
                node.getContent() != null ? node.getContent().getMimeType() : null,
                node.getName(),
                documentPath
        );
    }

    private HxprDocument createDocument(Node node) {
        String pathRepositoryId = resolvePathRepositoryId();
        String parentPath = buildContentLakeParentPath(node, pathRepositoryId);
        return createDocumentAtPath(node, parentPath);
    }

    private HxprDocument createDocumentAtPath(Node node, String parentPath) {
        hxprService.ensureFolder(parentPath);
        HxprDocument doc = buildDocument(node);
        doc.setCinPaths(List.of(joinPath(parentPath, node.getId())));
        try {
            HxprDocument created = hxprService.createDocument(parentPath, doc);
            log.info("Created hxpr document: {} for node: {} at path: {}",
                    created.getSysId(), node.getId(), joinPath(parentPath, node.getId()));
            return created;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new IllegalStateException("HXPR denied document creation at path '" + parentPath
                    + "'. Configure hxpr.path-repository-id (HXPR_PATH_REPOSITORY_ID) "
                    + "or grant write permissions for this hierarchy.", e);
        }
    }

    private String buildContentLakeParentPath(Node node, String repositoryId) {
        String base = buildRepositoryRootPath(repositoryId);
        if (node.getPath() == null
                || node.getPath().getName() == null
                || node.getPath().getName().isBlank()) {
            return base;
        }
        String alfrescoPath = normalizeAbsolutePath(node.getPath().getName());
        if ("/".equals(base)) {
            return alfrescoPath;
        }
        return base + alfrescoPath;
    }

    private String buildRepositoryRootPath(String repositoryId) {
        String targetPath = normalizeAbsolutePath(hxprProps.getTargetPath());
        if (repositoryId == null || repositoryId.isBlank()) {
            return targetPath;
        }
        String cleanRepositoryId = repositoryId.startsWith("/")
                ? repositoryId.substring(1)
                : repositoryId;
        return joinPath(targetPath, cleanRepositoryId);
    }

    private String resolvePathRepositoryId() {
        String configured = hxprProps.getPathRepositoryId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return alfrescoClient.getRepositoryId();
    }

    private String normalizeAbsolutePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String joinPath(String parentPath, String leaf) {
        String parent = normalizeAbsolutePath(parentPath);
        if ("/".equals(parent)) {
            return "/" + leaf;
        }
        return parent + "/" + leaf;
    }

    private HxprDocument updateDocument(HxprDocument existing, Node node) {
        HxprDocument doc = buildDocument(node);
        doc.setSysId(existing.getSysId());
        HxprDocument updated = documentApi.updateById(existing.getSysId(), doc);
        log.info("Updated hxpr document: {} for node: {}", updated.getSysId(), node.getId());
        return updated;
    }

    private HxprDocument buildDocument(Node node) {
        HxprDocument doc = new HxprDocument();

        doc.setSysPrimaryType(SYS_FILE);
        doc.setSysName(node.getId());
        doc.setSysMixinTypes(List.of(MIXIN_CIN_REMOTE));

        doc.setCinId(node.getId());
        doc.setCinSourceId(alfrescoClient.getRepositoryId());
        doc.setCinPaths(buildCinPaths(node));

        // Use sys_acl for permission enforcement (CIC-compatible ACE format)
        List<String> readerList = buildReaderList(node);
        doc.setSysAcl(buildSysAcl(readerList));

        // Ingest properties
        Map<String, Object> props = buildIngestProperties(node, doc.getCinSourceId());
        doc.setCinIngestProperties(props);
        doc.setCinIngestPropertyNames(new ArrayList<>(props.keySet()));

        doc.setSyncStatus(HxprDocument.SyncStatus.PENDING);
        doc.setSyncError(null);

        applyFlattenedAlfrescoFields(doc, node, doc.getCinSourceId(), readerList);

        return doc;
    }

    private List<String> buildReaderList(Node node) {
        Set<String> readers = alfrescoClient.extractReadAuthorities(node);

        if (log.isDebugEnabled()) {
            boolean hasPermissions = node.getPermissions() != null;
            Boolean inheritanceEnabled = hasPermissions ? node.getPermissions().isIsInheritanceEnabled() : null;
            Integer inheritedCount = (hasPermissions && node.getPermissions().getInherited() != null)
                    ? node.getPermissions().getInherited().size() : null;
            Integer localCount = (hasPermissions && node.getPermissions().getLocallySet() != null)
                    ? node.getPermissions().getLocallySet().size() : null;

            log.debug(
                    "Read authorities computed for nodeId={} name={} permissionsIncluded={} isInheritanceEnabled={} inheritedPerms={} locallySetPerms={} readersCount={}",
                    node.getId(), node.getName(), hasPermissions, inheritanceEnabled, inheritedCount, localCount, readers.size()
            );
        }

        return new ArrayList<>(readers);
    }

    /**
     * Builds a {@code sys_acl} list from the Alfresco read authorities.
     *
     * <p>Converts each authority into an {@link ACE} entry following the format
     * specified in the CIC Ingest contract:
     * <ul>
     *   <li>{@code GROUP_EVERYONE} → ACE with user id {@code __Everyone__}</li>
     *   <li>Authorities prefixed with {@code GROUP_} → ACE with a {@link Group} principal
     *       and id suffixed with {@code _#_<repositoryId>}</li>
     *   <li>User authorities → ACE with a {@link User} principal
     *       and id suffixed with {@code _#_<repositoryId>}</li>
     * </ul>
     *
     * <p>The {@code _#_<repositoryId>} suffix follows the CIC external identity syntax
     * for separating external user/group ids from the source system id.
     * The repository id is obtained from {@link AlfrescoClient#getRepositoryId()}.</p>
     */
    private List<ACE> buildSysAcl(List<String> authorities) {
        List<ACE> acl = new ArrayList<>();
        String suffix = "_#_" + alfrescoClient.getRepositoryId();

        for (String authority : authorities) {
            if ("GROUP_EVERYONE".equals(authority)) {
                // __Everyone__ is a well-known principal — never suffixed
                acl.add(buildUserAce(EVERYONE_PRINCIPAL));
            } else if (authority.startsWith(GROUP_PREFIX)) {
                acl.add(buildGroupAce(authority + suffix));
            } else {
                acl.add(buildUserAce(authority + suffix));
            }
        }

        return acl;
    }

    private ACE buildUserAce(String userId) {
        ACE ace = new ACE();
        ace.setGranted(true);
        ace.setPermission(PERMISSION_READ);
        User user = new User();
        user.setId(userId);
        ace.setUser(user);
        return ace;
    }

    private ACE buildGroupAce(String groupId) {
        ACE ace = new ACE();
        ace.setGranted(true);
        ace.setPermission(PERMISSION_READ);
        Group group = new Group();
        group.setId(groupId);
        ace.setGroup(group);
        return ace;
    }

    private Map<String, Object> buildIngestProperties(Node node, String repositoryId) {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put(P_ALF_NODE_ID, node.getId());
        props.put(P_ALF_REPO_ID, repositoryId);
        props.put(P_ALF_NAME, node.getName());
        props.put(P_ALF_PATH, node.getPath() != null ? node.getPath().getName() : null);
        props.put(P_ALF_MIME, node.getContent() != null ? node.getContent().getMimeType() : null);
        props.put(P_ALF_MODIFIED_AT, node.getModifiedAt() != null ? node.getModifiedAt().toString() : null);

        props.values().removeIf(Objects::isNull);
        return props;
    }

    private void applyFlattenedAlfrescoFields(HxprDocument doc, Node node, String repositoryId, List<String> readerList) {
        doc.setAlfrescoNodeId(node.getId());
        doc.setAlfrescoRepositoryId(repositoryId);
        doc.setAlfrescoName(node.getName());
        doc.setAlfrescoPath(node.getPath() != null ? node.getPath().getName() : null);
        doc.setAlfrescoMimeType(node.getContent() != null ? node.getContent().getMimeType() : null);
        doc.setAlfrescoModifiedAt(node.getModifiedAt() != null ? node.getModifiedAt().toString() : null);
        doc.setAlfrescoReadAuthorities(readerList);
    }

    private List<String> buildCinPaths(Node node) {
        String repositoryId = resolvePathRepositoryId();
        String parentPath = buildContentLakeParentPath(node, repositoryId);
        return List.of(joinPath(parentPath, node.getId()));
    }
}
