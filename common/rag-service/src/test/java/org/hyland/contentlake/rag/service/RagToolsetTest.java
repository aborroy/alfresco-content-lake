package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.SemanticSearchRequest;
import org.hyland.contentlake.rag.model.SemanticSearchResponse;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagToolsetTest {

    @Mock
    private SemanticSearchService semanticSearchService;
    @Mock
    private HxprService hxprService;

    private RagToolset toolset(int maxIterations) {
        RagProperties props = new RagProperties();
        props.getAgenticTools().setEnabled(true);
        props.getAgenticTools().setMaxIterations(maxIterations);
        return new RagToolset(semanticSearchService, hxprService, props);
    }

    private ToolContext context(Authentication auth, AtomicInteger iterations) {
        return new ToolContext(Map.of(
                RagToolset.CTX_AUTH, auth,
                RagToolset.CTX_ITERATIONS, iterations));
    }

    @Test
    void researchAgain_stopsAfterMaxIterations() {
        RagToolset toolset = toolset(2);
        AtomicInteger iterations = new AtomicInteger(0);
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "");
        when(semanticSearchService.search(any())).thenReturn(
                SemanticSearchResponse.builder().results(List.of()).build());

        // First two rounds are allowed.
        toolset.researchAgain("q1", null, context(auth, iterations));
        toolset.researchAgain("q2", null, context(auth, iterations));
        // Third exceeds the cap.
        String third = toolset.researchAgain("q3", null, context(auth, iterations));

        assertThat(third).contains("No further research permitted");
        verify(semanticSearchService, org.mockito.Mockito.times(2)).search(any());
    }

    @Test
    void researchAgain_appliesToolContextAuthDuringSearch() {
        RagToolset toolset = toolset(5);
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "");
        String[] seenPrincipal = new String[1];
        when(semanticSearchService.search(any())).thenAnswer(inv -> {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            seenPrincipal[0] = current != null ? current.getName() : null;
            SearchHit hit = SearchHit.builder().chunkText("some text").score(0.5).build();
            return SemanticSearchResponse.builder().results(List.of(hit)).build();
        });

        String result = toolset.researchAgain("find X", null, context(auth, new AtomicInteger(0)));

        assertThat(seenPrincipal[0]).isEqualTo("alice");
        assertThat(result).contains("some text");
        // Context is restored (cleared) after the tool returns.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void getDocument_usesPermissionScopedQuery_andReportsNoAccess() {
        RagToolset toolset = toolset(5);
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "");
        when(semanticSearchService.currentUserPermissionFilter(any(), any()))
                .thenReturn("SELECT * FROM SysContent WHERE (perm) AND (cin_id = 'doc-1')");
        when(hxprService.query(any(), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.hyland.contentlake.model.HxprDocument.QueryResult());

        String result = toolset.getDocument("doc-1", context(auth, new AtomicInteger(0)));

        assertThat(result).contains("No accessible document found");
        verify(semanticSearchService).currentUserPermissionFilter(any(), any());
    }

    @Test
    void researchAgain_neverAcceptsAPrincipalArgument() {
        // Structural guarantee: the only tool inputs are search params (query, filter). Identity comes
        // from the ToolContext, so a prompt-injected principal cannot widen access. If a search param
        // is absent, the tool still runs under the context auth (here: none -> anonymous search).
        RagToolset toolset = toolset(5);
        when(semanticSearchService.search(any()))
                .thenReturn(SemanticSearchResponse.builder().results(List.of()).build());

        toolset.researchAgain("q", null, new ToolContext(Map.of(RagToolset.CTX_ITERATIONS, new AtomicInteger(0))));

        // No auth in context -> search still invoked (ACL enforced inside search from SecurityContext).
        verify(semanticSearchService).search(any());
        verify(semanticSearchService, never()).currentUserPermissionFilter(any(), any());
    }
}
