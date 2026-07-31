package org.hyland.contentlake.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyland.contentlake.model.HxprEmbedding.EmbeddingLocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParquetEmbeddingWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializeLocation_null_returnsNull() throws Exception {
        assertThat(ParquetEmbeddingWriter.serializeLocation(null)).isNull();
    }

    @Test
    void serializeLocation_textOnly_writesParagraph() throws Exception {
        EmbeddingLocation location = new EmbeddingLocation();
        EmbeddingLocation.TextLocation text = new EmbeddingLocation.TextLocation();
        text.setParagraph(7);
        location.setText(text);

        String json = ParquetEmbeddingWriter.serializeLocation(location);

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("text").get("paragraph").asInt()).isEqualTo(7);
        // page is null -> omitted (NON_NULL); other location kinds absent
        assertThat(node.get("text").has("page")).isFalse();
        assertThat(node.has("position")).isFalse();
        assertThat(node.has("timestamp")).isFalse();
        assertThat(node.has("spreadsheet")).isFalse();
    }

    @Test
    void serializeLocation_allKinds_usesWireFieldNames() throws Exception {
        EmbeddingLocation location = new EmbeddingLocation();

        EmbeddingLocation.TextLocation text = new EmbeddingLocation.TextLocation();
        text.setPage(2);
        text.setParagraph(4);
        location.setText(text);

        EmbeddingLocation.PositionLocation position = new EmbeddingLocation.PositionLocation();
        position.setLeft(1);
        position.setTop(2);
        position.setRight(3);
        position.setBottom(4);
        location.setPosition(position);

        EmbeddingLocation.TimestampLocation timestamp = new EmbeddingLocation.TimestampLocation();
        timestamp.setStart(0.5);
        timestamp.setEnd(1.5);
        location.setTimestamp(timestamp);

        EmbeddingLocation.SpreadsheetLocation spreadsheet = new EmbeddingLocation.SpreadsheetLocation();
        spreadsheet.setColumn(1);
        spreadsheet.setRow(2);
        spreadsheet.setSheet("Sheet1");
        location.setSpreadSheet(spreadsheet);

        String json = ParquetEmbeddingWriter.serializeLocation(location);

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("text").get("page").asInt()).isEqualTo(2);
        assertThat(node.get("position").get("bottom").asInt()).isEqualTo(4);
        assertThat(node.get("timestamp").get("end").asDouble()).isEqualTo(1.5);
        // Field name is the wire form "spreadsheet", not the Java field "spreadSheet".
        assertThat(node.has("spreadsheet")).isTrue();
        assertThat(node.get("spreadsheet").get("sheet").asText()).isEqualTo("Sheet1");
    }
}
