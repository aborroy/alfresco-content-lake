package org.hyland.filesystem.contentlake.client;

import org.hyland.contentlake.spi.SourceNode;
import org.hyland.filesystem.contentlake.config.FileSystemProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSourceClientTest {

    @TempDir
    Path root;

    private FileSystemSourceClient client;

    @BeforeEach
    void setUp() {
        FileSystemProperties props = new FileSystemProperties();
        props.setRootPath(root.toString());
        props.setSourceId("fs-test");
        props.setReadPrincipals(Set.of("__Everyone__"));
        client = new FileSystemSourceClient(props);
    }

    @Test
    void getSourceTypeAndId() {
        assertThat(client.getSourceType()).isEqualTo("filesystem");
        assertThat(client.getSourceId()).isEqualTo("fs-test");
    }

    @Test
    void getNode_buildsFileSourceNodeWithSecurityConfig() throws IOException {
        Path file = root.resolve("report.txt");
        Files.writeString(file, "hello");

        SourceNode node = client.getNode(file.toAbsolutePath().normalize().toString());

        assertThat(node.folder()).isFalse();
        assertThat(node.name()).isEqualTo("report.txt");
        assertThat(node.sourceType()).isEqualTo("filesystem");
        assertThat(node.sourceId()).isEqualTo("fs-test");
        assertThat(node.readPrincipals()).containsExactly("__Everyone__");
        assertThat(node.security()).isNotNull();
        assertThat(node.security().permissions())
                .extracting(p -> p.identity())
                .containsExactly("__Everyone__");
        assertThat(node.sourceProperties()).containsEntry("source_type", "filesystem");
    }

    @Test
    void getChildren_listsEntriesPaged() throws IOException {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Files.createDirectory(root.resolve("sub"));

        List<SourceNode> firstPage = client.getChildren(root.toString(), 0, 2);
        List<SourceNode> secondPage = client.getChildren(root.toString(), 2, 2);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).extracting(SourceNode::name).contains("a.txt");
    }

    @Test
    void getContent_returnsFileBytes() throws IOException {
        Path file = root.resolve("data.txt");
        Files.writeString(file, "payload", StandardCharsets.UTF_8);

        byte[] content = client.getContent(file.toAbsolutePath().normalize().toString());

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("payload");
    }
}
