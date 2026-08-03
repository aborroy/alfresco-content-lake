package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.Query;
import org.hyland.contentlake.model.HxprDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for issue #80: re-syncing a node duplicated its embeddings because the
 * embedding-child lookup ran with the default {@code limit=0} (which hxpr treats as "return no
 * rows"), so the stale child was never found or deleted before a new one was created.
 */
@ExtendWith(MockitoExtension.class)
class HxprServiceEmbeddingChildTest {

    private static final String DOC_ID = "parent-doc-id";
    private static final String CHILD_NAME = "_e_mxbai-embed-large";

    @Mock
    private HxprDocumentApi documentApi;
    @Mock
    private HxprQueryApi queryApi;
    @Mock
    private RestClient restClient;

    @Test
    void deleteEmbeddings_queriesWithPositiveLimit_soChildrenAreActuallyFound() {
        HxprService service = new HxprService(documentApi, queryApi, restClient);
        when(queryApi.query(any(Query.class))).thenReturn(emptyResult());
        when(documentApi.getById(DOC_ID)).thenReturn(new HxprDocument());

        service.deleteEmbeddings(DOC_ID);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(queryApi).query(queryCaptor.capture());
        Query issued = queryCaptor.getValue();
        // The limit=0 default is the root cause of #80; the child lookup must ask for rows.
        assertThat(issued.getLimit()).isGreaterThan(0L);
        assertThat(issued.getQuery()).contains("sys_parentId = '" + DOC_ID + "'");
    }

    @Test
    void deleteEmbeddings_deletesEveryMatchingChild_notJustTheFirst() {
        HxprService service = new HxprService(documentApi, queryApi, restClient);
        // hxpr auto-suffixes duplicate child names; a re-sync leaves the base child plus a
        // suffixed sibling. All of them must be removed, not only results.get(0).
        when(queryApi.query(any(Query.class))).thenReturn(
                result(child("child-1", CHILD_NAME), child("child-2", CHILD_NAME + ".123456789")));
        when(documentApi.getById(DOC_ID)).thenReturn(new HxprDocument());

        service.deleteEmbeddings(DOC_ID);

        verify(documentApi).deleteById("child-1");
        verify(documentApi).deleteById("child-2");
    }

    @Test
    void deleteEmbeddings_waitsForIndexBeforeQuerying_soRecentChildrenAreVisible() {
        HxprService service = new HxprService(documentApi, queryApi, restClient);
        when(queryApi.query(any(Query.class))).thenReturn(emptyResult());
        when(documentApi.getById(DOC_ID)).thenReturn(new HxprDocument());

        service.deleteEmbeddings(DOC_ID);

        // The index wait must happen BEFORE the lookup, otherwise a child created on a prior
        // sync may not yet be visible and a duplicate would be created on re-sync (#80).
        InOrder inOrder = inOrder(queryApi);
        inOrder.verify(queryApi).waitForFullTextSearchIndexing(anyBoolean(), anyInt());
        inOrder.verify(queryApi).query(any(Query.class));
    }

    private static HxprDocument child(String id, String name) {
        HxprDocument doc = new HxprDocument();
        doc.setSysId(id);
        doc.setSysName(name);
        return doc;
    }

    private static HxprDocument.QueryResult emptyResult() {
        return result();
    }

    private static HxprDocument.QueryResult result(HxprDocument... docs) {
        HxprDocument.QueryResult qr = new HxprDocument.QueryResult();
        qr.setDocuments(List.of(docs));
        return qr;
    }
}
