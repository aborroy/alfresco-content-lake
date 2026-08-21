package org.hyland.contentlake.client;

import org.hyland.contentlake.hxpr.api.model.VocabularyEntry;
import org.hyland.contentlake.hxpr.api.model.VocabularyId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VocabularyServiceTest {

    @Mock
    private HxprVocabularyApi vocabularyApi;

    private static VocabularyId vocab(String id) {
        VocabularyId v = new VocabularyId();
        v.setSysvocabId(id);
        return v;
    }

    private static VocabularyEntry entry(String key, String label) {
        VocabularyEntry e = new VocabularyEntry();
        e.setSysvocabKey(key);
        e.setSysvocabLabel(label);
        return e;
    }

    @Test
    void resolve_mapsLabelToCanonicalKey() {
        when(vocabularyApi.getVocabularyList()).thenReturn(List.of(vocab("departments")));
        when(vocabularyApi.getVocabularyEntries("departments"))
                .thenReturn(List.of(entry("hr", "Human Resources"), entry("fin", "Finance")));

        VocabularyService service = new VocabularyService(vocabularyApi);

        assertThat(service.resolve("Human Resources")).isEqualTo("hr");
        assertThat(service.resolve("Finance")).isEqualTo("fin");
    }

    @Test
    void resolve_unknownValueOrBlank_passesThrough() {
        lenient().when(vocabularyApi.getVocabularyList()).thenReturn(List.of());
        VocabularyService service = new VocabularyService(vocabularyApi);

        assertThat(service.resolve("hr-docs")).isEqualTo("hr-docs");
        assertThat(service.resolve("")).isEqualTo("");
        assertThat(service.resolve(null)).isNull();
    }

    @Test
    void resolve_cachesTheMapAcrossCalls() {
        when(vocabularyApi.getVocabularyList()).thenReturn(List.of(vocab("d")));
        when(vocabularyApi.getVocabularyEntries("d")).thenReturn(List.of(entry("hr", "HR")));

        VocabularyService service = new VocabularyService(vocabularyApi);
        service.resolve("HR");
        service.resolve("HR");

        verify(vocabularyApi, times(1)).getVocabularyList();
    }

    @Test
    void resolve_hxprFailure_degradesToPassThrough() {
        when(vocabularyApi.getVocabularyList()).thenThrow(new RuntimeException("hxpr down"));
        VocabularyService service = new VocabularyService(vocabularyApi);

        assertThat(service.resolve("Human Resources")).isEqualTo("Human Resources");
    }

    @Test
    void invalidate_forcesReload() {
        when(vocabularyApi.getVocabularyList()).thenReturn(List.of(vocab("d")));
        when(vocabularyApi.getVocabularyEntries("d")).thenReturn(List.of(entry("hr", "HR")));

        VocabularyService service = new VocabularyService(vocabularyApi);
        service.resolve("HR");
        service.invalidate();
        service.resolve("HR");

        verify(vocabularyApi, times(2)).getVocabularyList();
    }
}
