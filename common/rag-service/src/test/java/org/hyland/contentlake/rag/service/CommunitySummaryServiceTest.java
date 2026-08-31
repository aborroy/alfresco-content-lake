package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprDocumentApi;
import org.hyland.contentlake.client.HxprGraphService;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #84: community detection merges duplicate GlobalEntity nodes by canonical name, so a community is
 * counted over the union of its members rather than split/undercounted across per-JVM duplicates.
 */
@ExtendWith(MockitoExtension.class)
class CommunitySummaryServiceTest {

    @Mock
    private HxprGraphService graphService;
    @Mock
    private HxprService hxprService;
    @Mock
    private HxprDocumentApi documentApi;
    @Mock
    private ChatModel chatModel;

    private RagProperties properties;
    private CommunitySummaryService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getGraph().setEnabled(true);
        // minSize 2 so a single duplicate node (1 member) is below threshold but the union (2) clears it.
        properties.getGraph().getCommunities().setMinSize(2);
        service = new CommunitySummaryService(graphService, hxprService, documentApi, chatModel, properties);
    }

    @Test
    void mergesDuplicateEntityNodes_intoOneCommunity() {
        when(graphService.resolveGraphDbId(any(), eq("content-lake"))).thenReturn("gdb-1");
        // Two duplicate "Acme Corp" nodes, each mentioning ONE distinct document. Only their union
        // (doc-a + doc-b = 2) reaches minSize=2; each node alone (1) would not.
        when(graphService.query(eq("gdb-1"), any(), any())).thenReturn(
                "{\"queryGlobalEntity\":["
                        + "{\"canonical_name\":\"Acme Corp\",\"mentioned_in\":[{\"documentId\":\"doc-a\"}]},"
                        + "{\"canonical_name\":\"Acme Corp\",\"mentioned_in\":[{\"documentId\":\"doc-b\"}]}]}");
        // Member fetch returns both docs with extractable text so the summary has a body.
        when(hxprService.query(any(), anyInt(), eq(0)))
                .thenReturn(queryResult(member("doc-a", "Acme launched X."), member("doc-b", "Acme acquired Y.")));
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Acme Corp summary.")))));
        when(hxprService.findByPath(any())).thenReturn(null);

        int written = service.rebuild();

        // The two duplicates collapsed into exactly one community, written once.
        assertThat(written).isEqualTo(1);
        verify(hxprService).createDocument(eq("/_graph/communities"), any(HxprDocument.class));
    }

    private static HxprDocument member(String sysId, String text) {
        HxprDocument d = new HxprDocument();
        d.setSysId(sysId);
        d.setSysName(sysId + ".txt");
        d.setCinIngestProperties(Map.of("contentLake_extractedText", text));
        return d;
    }

    private static HxprDocument.QueryResult queryResult(HxprDocument... docs) {
        HxprDocument.QueryResult qr = new HxprDocument.QueryResult();
        qr.setDocuments(List.of(docs));
        return qr;
    }
}
