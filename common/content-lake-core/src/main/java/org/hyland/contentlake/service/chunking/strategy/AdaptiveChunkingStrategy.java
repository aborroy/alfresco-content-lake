package org.hyland.contentlake.service.chunking.strategy;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.model.Chunk;
import org.hyland.contentlake.model.ChunkType;
import org.hyland.contentlake.service.chunking.strategy.TextSegmenter.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive chunking strategy that works for all document types.
 *
 * <p>Strategy hierarchy (tries in order until chunks fit):
 * <ol>
 *   <li>Section-level splitting (headings, chapters)</li>
 *   <li>Paragraph-level splitting (double newlines)</li>
 *   <li>Sentence-level splitting (periods, exclamation marks)</li>
 *   <li>Hard character-based splitting (last resort)</li>
 * </ol>
 *
 * <p>Guarantees: No chunk will exceed maxChunkSize, even for pathological inputs.
 */
@Slf4j
public class AdaptiveChunkingStrategy implements ChunkingStrategy {

    private static final String STRATEGY_NAME = "adaptive";

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public List<Chunk> chunk(String text, String nodeId, ChunkingConfig config) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Step 1: Try section-level (headings), keeping detected tables as atomic segments
        List<TextSegment> segments = TextSegmenter.splitSectionsAndTables(text);

        // Step 2: If sections are too large or too few, try paragraphs (tables are passed through)
        if (hasOversizedSegments(segments, config.maxChunkSize())) {
            segments = splitRecursive(segments, config.maxChunkSize(), config.overlapSize());
        }

        // Step 3: Group segments while enforcing size limits (with overlap)
        List<TextSegment> grouped = groupWithHardLimit(segments, config);

        // Step 4: Convert to chunks
        return toChunks(grouped, nodeId);
    }

    /**
     * Recursively splits oversized segments down to the required size.
     * Tries: paragraphs → sentences → hard split (with overlap).
     */
    private List<TextSegment> splitRecursive(List<TextSegment> segments, int maxSize, int overlapSize) {
        List<TextSegment> result = new ArrayList<>();

        for (TextSegment segment : segments) {
            // Tables are atomic: never paragraph/sentence/hard split. Oversized tables are handled
            // by row-group splitting in groupWithHardLimit so the header row is repeated.
            if (segment.table()) {
                result.add(segment);
                continue;
            }

            if (segment.length() <= maxSize) {
                result.add(segment);
                continue;
            }

            int section = segment.sectionIndex();

            // Try splitting by paragraphs
            List<TextSegment> paragraphs = TextSegmenter.splitParagraphs(segment.text());
            if (paragraphs.size() > 1 && !hasOversizedSegments(paragraphs, maxSize)) {
                addAllWithSection(result, paragraphs, section);
                continue;
            }

            // Try splitting by sentences
            List<TextSegment> sentences = TextSegmenter.splitSentences(segment.text());
            if (sentences.size() > 1 && !hasOversizedSegments(sentences, maxSize)) {
                addAllWithSection(result, sentences, section);
                continue;
            }

            // Last resort: hard split by character count (with overlap)
            log.warn("Oversized segment ({} chars) requires hard splitting at {} char boundary",
                    segment.length(), maxSize);
            addAllWithSection(result, hardSplit(segment.text(), maxSize, overlapSize), section);
        }

        return result;
    }

    /** Adds sub-segments to {@code result}, reassigning each to the parent segment's section. */
    private void addAllWithSection(List<TextSegment> result, List<TextSegment> parts, int sectionIndex) {
        for (TextSegment part : parts) {
            result.add(part.withSection(sectionIndex));
        }
    }

    /**
     * Groups segments while enforcing a HARD limit on chunk size, with overlap.
     *
     * <p>When a chunk is flushed, the last {@code overlapSize} characters (broken at a
     * word boundary) are carried into the start of the next chunk. This preserves
     * context at chunk boundaries, which is critical for retrieval quality — without
     * overlap, sentences or ideas that straddle two chunks may not match any single
     * chunk's embedding well enough to be retrieved.</p>
     *
     * <p>If a single segment exceeds maxSize, it will be split recursively first.</p>
     */
    private List<TextSegment> groupWithHardLimit(List<TextSegment> segments, ChunkingConfig config) {
        List<TextSegment> grouped = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = -1;
        int currentEnd = 0;
        int currentSection = 0;
        // A group's section is that of its first non-overlap segment. Overlap carried across a flush
        // does not count, so a chunk that is mostly the next section is labelled with that section.
        boolean hasRealSegment = false;

        int overlapSize = config.overlapSize();

        for (TextSegment segment : segments) {
            String text = segment.text();

            // Tables never merge with surrounding prose and are never mid-row split: flush any
            // pending prose (no overlap carry across a table boundary), then emit the table as its
            // own chunk(s), row-group splitting with a repeated header when it exceeds maxChunkSize.
            if (segment.table()) {
                if (!current.isEmpty()) {
                    grouped.add(new TextSegment(current.toString().strip(), currentStart, currentEnd,
                            false, currentSection));
                    current.setLength(0);
                    currentStart = -1;
                    hasRealSegment = false;
                }
                grouped.addAll(TextSegmenter.splitTableByRowGroups(segment, config.maxChunkSize()));
                continue;
            }

            // CRITICAL FIX: If this single segment is already too large, split it
            if (text.length() > config.maxChunkSize()) {
                // Flush current accumulated content (with overlap carry)
                if (!current.isEmpty()) {
                    String flushed = current.toString().strip();
                    grouped.add(new TextSegment(flushed, currentStart, currentEnd, false, currentSection));

                    // Carry overlap tail into the next chunk
                    String overlapText = extractOverlapTail(flushed, overlapSize);
                    current.setLength(0);
                    if (!overlapText.isEmpty()) {
                        current.append(overlapText);
                        // Overlap region starts from within the previous chunk's span
                        currentStart = currentEnd - overlapText.length();
                    } else {
                        currentStart = -1;
                    }
                }

                // Split the oversized segment and add all parts
                List<TextSegment> split = splitRecursive(List.of(segment), config.maxChunkSize(), config.overlapSize());
                grouped.addAll(split);

                // After injecting split segments, reset accumulator (overlap from the
                // last split segment will be handled naturally on the next flush)
                current.setLength(0);
                currentStart = -1;
                hasRealSegment = false;
                continue;
            }

            // Would adding this segment exceed max?
            if (current.length() + text.length() + 1 > config.maxChunkSize()
                    && current.length() >= config.minChunkSize()) {
                // Flush current group
                String flushed = current.toString().strip();
                grouped.add(new TextSegment(flushed, currentStart, currentEnd, false, currentSection));

                // Carry overlap tail into the next chunk
                String overlapText = extractOverlapTail(flushed, overlapSize);
                current.setLength(0);
                if (!overlapText.isEmpty()) {
                    current.append(overlapText);
                    currentStart = currentEnd - overlapText.length();
                } else {
                    currentStart = -1;
                }
                hasRealSegment = false;
            }

            if (currentStart < 0) {
                currentStart = segment.startOffset();
            }
            if (!hasRealSegment) {
                currentSection = segment.sectionIndex();
                hasRealSegment = true;
            }

            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(text);
            currentEnd = segment.endOffset();
        }

        // Flush remaining (no overlap needed for the last chunk)
        if (!current.isEmpty()) {
            grouped.add(new TextSegment(current.toString().strip(), currentStart, currentEnd,
                    false, currentSection));
        }

        return grouped;
    }

    /**
     * Extracts the last {@code targetLength} characters from text, breaking at a word
     * boundary to avoid splitting words. Returns empty string if the text is too short
     * to produce a meaningful overlap.
     *
     * @param text         the flushed chunk text
     * @param targetLength desired overlap length in characters
     * @return overlap tail text, or empty string
     */
    private String extractOverlapTail(String text, int targetLength) {
        if (targetLength <= 0 || text.length() <= targetLength) {
            return "";
        }

        // Start from (length - targetLength) and find the next word boundary
        int cutPoint = text.length() - targetLength;

        // Move forward to the next space to avoid splitting a word
        int nextSpace = text.indexOf(' ', cutPoint);
        if (nextSpace > 0 && nextSpace < text.length() - 1) {
            cutPoint = nextSpace + 1;
        }

        String overlap = text.substring(cutPoint).strip();

        // Don't return trivially short overlaps (less than ~20 chars is noise)
        return overlap.length() >= 20 ? overlap : "";
    }

    /**
     * Hard split at character boundaries (last resort), with overlap.
     *
     * <p>Advances by {@code maxSize - overlapSize} per iteration so that consecutive
     * chunks share an overlapping region of approximately {@code overlapSize} characters.</p>
     */
    private List<TextSegment> hardSplit(String text, int maxSize) {
        return hardSplit(text, maxSize, 0);
    }

    /**
     * Hard split with configurable overlap.
     */
    private List<TextSegment> hardSplit(String text, int maxSize, int overlapSize) {
        List<TextSegment> segments = new ArrayList<>();
        int stride = Math.max(maxSize / 2, maxSize - overlapSize); // Ensure forward progress
        int offset = 0;

        while (offset < text.length()) {
            int end = Math.min(offset + maxSize, text.length());

            // Try to break at a space to avoid splitting words
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > offset + (maxSize / 2)) { // Only if not too far back
                    end = lastSpace;
                }
            }

            String chunk = text.substring(offset, end).strip();
            if (!chunk.isEmpty()) {
                segments.add(new TextSegment(chunk, offset, end));
            }

            // Advance by stride (not end) to create overlap region.
            // For the last chunk we break out to avoid an infinite loop.
            if (end >= text.length()) {
                break;
            }
            offset += stride;
        }

        return segments;
    }

    private boolean hasOversizedSegments(List<TextSegment> segments, int maxSize) {
        return segments.stream().anyMatch(seg -> seg.length() > maxSize);
    }

    private List<Chunk> toChunks(List<TextSegment> segments, String nodeId) {
        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            Chunk chunk = new Chunk(
                    nodeId,
                    seg.text(),
                    i,
                    seg.startOffset(),
                    seg.endOffset(),
                    strategyName());
            chunk.setChunkType(seg.table() ? ChunkType.TABLE : ChunkType.PROSE);
            chunk.setSectionIndex(seg.sectionIndex());
            chunks.add(chunk);
        }
        return chunks;
    }
}