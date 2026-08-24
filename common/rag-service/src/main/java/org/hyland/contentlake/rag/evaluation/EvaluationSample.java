package org.hyland.contentlake.rag.evaluation;

import java.util.List;

/**
 * One evaluation case: a question, its expected answer, and the source documents that should back
 * the answer.
 *
 * @param question         the question to run through the RAG pipeline
 * @param expectedAnswer   the reference answer (informational; scored by the external harness)
 * @param expectedSourceIds identifiers of documents expected among the retrieved sources; matched
 *                          loosely against each source's name, node id, source id, or document id
 */
public record EvaluationSample(String question, String expectedAnswer, List<String> expectedSourceIds) {
}
