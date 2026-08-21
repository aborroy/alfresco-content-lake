package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.NamedQueryDefinition;
import org.hyland.contentlake.hxpr.api.model.WhereClause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamedQueryServiceTest {

    @Mock
    private HxprService hxprService;

    private NamedQueryService service() {
        return new NamedQueryService(hxprService);
    }

    private static NamedQueryDefinition definitionWithWhere(String hxql) {
        NamedQueryDefinition def = new NamedQueryDefinition();
        WhereClause where = new WhereClause();
        where.setQuery(hxql);
        def.setWhereClauseDefinition(where);
        return def;
    }

    @Test
    void resolveFilter_returnsTheWhereClauseHxql() {
        when(hxprService.getNamedQuery("articles"))
                .thenReturn(definitionWithWhere("mydc_nature = 'article'"));

        assertThat(service().resolveFilter("articles")).isEqualTo("mydc_nature = 'article'");
    }

    @Test
    void resolveFilter_blankName_isNoOpAndDoesNotCallHxpr() {
        assertThat(service().resolveFilter("  ")).isNull();
        assertThat(service().resolveFilter(null)).isNull();
        verifyNoInteractions(hxprService);
    }

    @Test
    void resolveFilter_unknownQuery_returnsNull() {
        when(hxprService.getNamedQuery("missing")).thenReturn(null);
        assertThat(service().resolveFilter("missing")).isNull();
    }

    @Test
    void resolveFilter_definitionWithoutWhereClause_returnsNull() {
        when(hxprService.getNamedQuery("empty")).thenReturn(new NamedQueryDefinition());
        assertThat(service().resolveFilter("empty")).isNull();
    }

    @Test
    void resolveFilter_fetchFailure_returnsNullRatherThanThrowing() {
        lenient().when(hxprService.getNamedQuery("boom")).thenThrow(new RuntimeException("hxpr down"));
        assertThat(service().resolveFilter("boom")).isNull();
    }
}
