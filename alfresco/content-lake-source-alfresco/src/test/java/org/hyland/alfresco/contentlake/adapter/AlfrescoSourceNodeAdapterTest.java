package org.hyland.alfresco.contentlake.adapter;

import org.alfresco.core.model.Node;
import org.alfresco.core.model.PermissionsInfo;
import org.hyland.contentlake.spi.PermissionRule;
import org.hyland.contentlake.spi.SourceNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlfrescoSourceNodeAdapterTest {

    @Test
    void toSourceNode_populatesStructuredSecurityConfigWithUserAndGroupRules() {
        Node node = new Node()
                .id("node-1")
                .name("report.pdf")
                .isFolder(false)
                .permissions(new PermissionsInfo().isInheritanceEnabled(true));

        SourceNode result = AlfrescoSourceNodeAdapter.toSourceNode(
                node, "alfresco-repo", Set.of("user-a", "GROUP_engineering"));

        assertThat(result.security()).isNotNull();
        assertThat(result.security().inheritanceEnabled()).isTrue();
        assertThat(result.security().permissions())
                .containsExactlyInAnyOrder(
                        new PermissionRule("user-a", "user", "user-a", "READ"),
                        new PermissionRule("GROUP_engineering", "group", "GROUP_engineering", "READ"));
    }

    @Test
    void toSourceNode_reportsInheritanceDisabledFromNodePermissions() {
        Node node = new Node()
                .id("node-2")
                .name("secret.txt")
                .isFolder(false)
                .permissions(new PermissionsInfo().isInheritanceEnabled(false));

        SourceNode result = AlfrescoSourceNodeAdapter.toSourceNode(
                node, "alfresco-repo", Set.of("user-a"));

        assertThat(result.security().inheritanceEnabled()).isFalse();
    }

    @Test
    void toSourceNode_treatsMissingPermissionBlockAsInheritanceEnabled() {
        Node node = new Node().id("node-3").name("plain.txt").isFolder(false);

        SourceNode result = AlfrescoSourceNodeAdapter.toSourceNode(
                node, "alfresco-repo", Set.of());

        assertThat(result.security()).isNotNull();
        assertThat(result.security().inheritanceEnabled()).isTrue();
        assertThat(result.security().permissions()).isEmpty();
    }
}
