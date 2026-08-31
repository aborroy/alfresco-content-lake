package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.FeedbackRating;
import org.hyland.contentlake.rag.model.FeedbackRecord;
import org.hyland.contentlake.rag.model.FeedbackRequest;
import org.hyland.contentlake.security.SecurityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock HxprService hxprService;
    @Mock SecurityContextService securityContextService;

    private RagProperties ragProperties;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        service = new FeedbackService(hxprService, securityContextService, ragProperties);
    }

    @Test
    void store_disabled_returnsNullAndTouchesNothing() {
        ragProperties.getFeedback().setEnabled(false);

        String id = service.store(FeedbackRequest.builder().rating(FeedbackRating.DOWN).build());

        assertThat(id).isNull();
        verifyNoInteractions(hxprService);
    }

    @Test
    void store_writesCinRemoteDocumentWithMirroredPropertyNames() {
        when(securityContextService.getCurrentUsername()).thenReturn("alice");

        FeedbackRequest request = FeedbackRequest.builder()
                .sessionId("user:alice")
                .requestId("req-1")
                .rating(FeedbackRating.DOWN)
                .comment("wrong number")
                .question("What is the retention period?")
                .answer("It is 3 years.")
                .sourceNodeIds(List.of("node-a", "node-b"))
                .build();

        String id = service.store(request);

        assertThat(id).isNotBlank();
        verify(hxprService).ensureFolder("/_feedback");

        ArgumentCaptor<HxprDocument> captor = ArgumentCaptor.forClass(HxprDocument.class);
        verify(hxprService).createDocument(eq("/_feedback"), captor.capture());
        HxprDocument doc = captor.getValue();

        assertThat(doc.getSysPrimaryType()).isEqualTo("SysFile");
        assertThat(doc.getSysMixinTypes()).contains("CinRemote");
        assertThat(doc.getSysName()).isEqualTo(id);
        // cin_ingestPropertyNames must always mirror cin_ingestProperties.keySet().
        assertThat(doc.getCinIngestPropertyNames())
                .containsExactlyInAnyOrderElementsOf(doc.getCinIngestProperties().keySet());

        Map<String, Object> props = doc.getCinIngestProperties();
        assertThat(props.get(FeedbackService.PROP_RATING)).isEqualTo("DOWN");
        assertThat(props.get(FeedbackService.PROP_REQUEST_ID)).isEqualTo("req-1");
        assertThat(props.get(FeedbackService.PROP_SOURCES)).isEqualTo("node-a,node-b");
        assertThat(props.get(FeedbackService.PROP_CREATED_AT)).isNotNull();

        // Feedback is scoped to the submitter, not world-readable.
        assertThat(doc.getCinRead()).containsExactly("alice");
    }

    @Test
    void store_swallowsPersistenceFailure() {
        when(securityContextService.getCurrentUsername()).thenReturn("bob");
        when(hxprService.createDocument(anyString(), any())).thenThrow(new RuntimeException("hxpr down"));

        String id = service.store(FeedbackRequest.builder().rating(FeedbackRating.UP).build());

        assertThat(id).isNull();
    }

    @Test
    void list_parsesDownRatedRecords() {
        HxprDocument doc = new HxprDocument();
        doc.setSysName("fb-1");
        doc.setCinIngestProperties(Map.of(
                FeedbackService.PROP_RATING, "DOWN",
                FeedbackService.PROP_REQUEST_ID, "req-9",
                FeedbackService.PROP_QUESTION, "Q?",
                FeedbackService.PROP_SOURCES, "n1,n2",
                FeedbackService.PROP_CREATED_AT, "2026-08-31T00:00:00Z"));

        HxprDocument.QueryResult result = mock(HxprDocument.QueryResult.class);
        when(result.getDocuments()).thenReturn(List.of(doc));
        when(hxprService.query(anyString(), anyInt(), anyInt())).thenReturn(result);

        List<FeedbackRecord> records = service.list(FeedbackRating.DOWN, 50);

        assertThat(records).hasSize(1);
        FeedbackRecord record = records.getFirst();
        assertThat(record.getFeedbackId()).isEqualTo("fb-1");
        assertThat(record.getRating()).isEqualTo(FeedbackRating.DOWN);
        assertThat(record.getRequestId()).isEqualTo("req-9");
        assertThat(record.getSourceNodeIds()).containsExactly("n1", "n2");
    }

    @Test
    void list_disabled_returnsEmpty() {
        ragProperties.getFeedback().setEnabled(false);
        assertThat(service.list(FeedbackRating.DOWN, 50)).isEmpty();
        verify(hxprService, never()).query(anyString(), anyInt(), anyInt());
    }
}
