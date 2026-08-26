package org.hyland.contentlake.rag.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight heuristic scanner for prompt-injection / instruction-override phrasing embedded in
 * retrieved document content (#71).
 *
 * <p>This is intentionally a fast pattern check, not an LLM classifier: it runs on every retrieved
 * chunk before context assembly. It is advisory - callers log matches for audit rather than dropping
 * evidence - so a false positive costs a log line, not a missing answer.</p>
 */
@Service
public class PromptInjectionScanner {

    private static final List<Pattern> PATTERNS = List.of(
            compile("ignore (?:all|any|the)?\\s*(?:previous|prior|above|earlier)\\s+instructions"),
            compile("disregard (?:all|any|the)?\\s*(?:previous|prior|above|earlier)\\s+instructions"),
            compile("forget (?:all|any|everything|the)?\\s*(?:previous|prior|above)?\\s*instructions"),
            compile("(?:reveal|print|show|repeat|disclose)\\s+(?:the|your)?\\s*system\\s+prompt"),
            compile("you are now\\b"),
            compile("act as\\b"),
            compile("(?:^|\\n)\\s*system\\s*:"),
            compile("(?:^|\\n)\\s*assistant\\s*:"),
            compile("new instructions\\s*:"),
            compile("override (?:the|your|all)?\\s*(?:previous|prior|system)?\\s*instructions"));

    /** Result of scanning a chunk: whether it matched, and the first pattern it matched. */
    public record ScanResult(boolean flagged, String matchedPattern) {
        private static final ScanResult CLEAN = new ScanResult(false, null);

        static ScanResult clean() {
            return CLEAN;
        }
    }

    /**
     * Scans a single retrieved chunk's text for known injection phrasing.
     *
     * @param chunkText the chunk text (may be {@code null})
     * @return a {@link ScanResult}; {@code flagged=false} when nothing matched or the text is blank
     */
    public ScanResult scan(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return ScanResult.clean();
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(chunkText).find()) {
                return new ScanResult(true, pattern.pattern());
            }
        }
        return ScanResult.clean();
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
