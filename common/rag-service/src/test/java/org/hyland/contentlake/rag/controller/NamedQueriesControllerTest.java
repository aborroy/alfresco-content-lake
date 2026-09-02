package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.client.NamedQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamedQueriesControllerTest {

    @Mock
    NamedQueryService namedQueryService;

    @InjectMocks
    NamedQueriesController controller;

    @Test
    void list_returnsRegisteredNames() {
        when(namedQueryService.list()).thenReturn(List.of("recent-contracts", "hr-policies"));

        assertThat(controller.list()).containsExactly("recent-contracts", "hr-policies");
    }

    @Test
    void list_empty_returnsEmptyList() {
        when(namedQueryService.list()).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
    }
}
