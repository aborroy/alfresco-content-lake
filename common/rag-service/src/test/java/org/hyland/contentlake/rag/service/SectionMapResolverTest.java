package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.model.SectionMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SectionMapResolverTest {

    private final SectionMapResolver resolver = new SectionMapResolver();

    private static SectionMap sampleMap() {
        return new SectionMap(
                List.of(0, 1),
                List.of(new SectionMap.Section(0, "PROSE", "prose text"),
                        new SectionMap.Section(1, "TABLE", "table text")));
    }

    @Test
    void chunkType_mapsChunkIndexToSectionType() {
        SectionMap map = sampleMap();
        assertThat(resolver.chunkType(map, 0)).isEqualTo("PROSE");
        assertThat(resolver.chunkType(map, 1)).isEqualTo("TABLE");
    }

    @Test
    void chunkType_nullOrOutOfRange_returnsNull() {
        SectionMap map = sampleMap();
        assertThat(resolver.chunkType(null, 0)).isNull();
        assertThat(resolver.chunkType(map, null)).isNull();
        assertThat(resolver.chunkType(map, 5)).isNull();
        assertThat(resolver.chunkType(map, -1)).isNull();
    }

    @Test
    void parse_readsSectionMapFromIngestProperties() {
        String json = "{\"chunkSections\":[0,1],\"sections\":["
                + "{\"index\":0,\"type\":\"PROSE\",\"text\":\"a\"},"
                + "{\"index\":1,\"type\":\"TABLE\",\"text\":\"b\"}]}";
        HxprDocument doc = new HxprDocument();
        doc.setCinIngestProperties(Map.of(ContentLakeIngestProperties.CONTENT_LAKE_SECTION_MAP, json));

        SectionMap map = resolver.parse(doc);

        assertThat(map).isNotNull();
        assertThat(resolver.chunkType(map, 1)).isEqualTo("TABLE");
    }

    @Test
    void parse_missingPropertyOrNullDoc_returnsNull() {
        assertThat(resolver.parse(null)).isNull();

        HxprDocument empty = new HxprDocument();
        assertThat(resolver.parse(empty)).isNull();

        HxprDocument noSectionMap = new HxprDocument();
        noSectionMap.setCinIngestProperties(Map.of("other", "value"));
        assertThat(resolver.parse(noSectionMap)).isNull();
    }
}
