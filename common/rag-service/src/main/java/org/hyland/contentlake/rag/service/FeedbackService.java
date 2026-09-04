package org.hyland.contentlake.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.model.FeedbackRating;
import org.hyland.contentlake.rag.model.FeedbackRecord;
import org.hyland.contentlake.rag.model.FeedbackRequest;
import org.hyland.contentlake.security.AclFilterBuilder;
import org.hyland.contentlake.security.SecurityContextService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists user feedback on generated answers (#74) as hxpr documents under a configured folder
 * (default {@code /_feedback}), and lists them for the offline evaluation harness.
 *
 * <p>Follows the same write convention as {@link org.hyland.contentlake.rag.conversation.SessionSummaryService}:
 * a {@code SysFile} carrying the {@code CinRemote} mixin, with
 * the feedback fields stored in {@code cin_ingestProperties} (and {@code cin_ingestPropertyNames}
 * kept mirrored). Each feedback entry is a new document keyed by a generated id, so there is no
 * update path.</p>
 *
 * <p>A feedback entry records a user's question and the answer they were given, so it is readable by
 * its submitter and by nobody else. That is enforced here rather than by the ACL: the listing runs
 * through the raw hxpr query API, and every query carries a predicate on the stored submitter. The
 * aggregate view the evaluation harness needs is a separate method, restricted to the accounts in
 * {@code rag.feedback.operator-users}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    static final String PROP_SUBMITTER = "feedback_submitter";
    static final String PROP_SESSION_ID = "feedback_sessionId";
    static final String PROP_REQUEST_ID = "feedback_requestId";
    static final String PROP_RATING = "feedback_rating";
    static final String PROP_COMMENT = "feedback_comment";
    static final String PROP_QUESTION = "feedback_question";
    static final String PROP_ANSWER = "feedback_answer";
    static final String PROP_SOURCES = "feedback_sources";
    static final String PROP_CREATED_AT = "feedback_createdAt";

    private static final String SOURCE_SEPARATOR = ",";

    private final HxprService hxprService;
    private final SecurityContextService securityContextService;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return ragProperties.getFeedback().isEnabled();
    }

    /**
     * Persists one feedback entry and returns its stable id, or {@code null} when feedback capture is
     * disabled or the write fails (feedback is operational metadata; a failure must never surface as a
     * request error to the user).
     */
    public String store(FeedbackRequest request) {
        if (!isEnabled()) {
            return null;
        }
        if (request == null || request.getRating() == null) {
            throw new IllegalArgumentException("rating is required");
        }

        String feedbackId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        String submitter = securityContextService.getCurrentUsername();

        Map<String, Object> props = new LinkedHashMap<>();
        // The submitter is a stored property, not only an ACL field, because it is what the listing
        // endpoint filters on: a feedback document is readable through it by its submitter alone.
        put(props, PROP_SUBMITTER, submitter);
        put(props, PROP_SESSION_ID, request.getSessionId());
        put(props, PROP_REQUEST_ID, request.getRequestId());
        put(props, PROP_RATING, request.getRating().name());
        put(props, PROP_COMMENT, request.getComment());
        put(props, PROP_QUESTION, request.getQuestion());
        put(props, PROP_ANSWER, request.getAnswer());
        if (request.getSourceNodeIds() != null && !request.getSourceNodeIds().isEmpty()) {
            props.put(PROP_SOURCES, String.join(SOURCE_SEPARATOR, request.getSourceNodeIds()));
        }
        props.put(PROP_CREATED_AT, createdAt);

        try {
            String basePath = basePath();
            hxprService.ensureFolder(basePath);

            HxprDocument doc = new HxprDocument();
            doc.setSysPrimaryType("SysFile");
            doc.setSysMixinTypes(List.of(HxprDocument.MIXIN_CIN_REMOTE));
            doc.setSysName(feedbackId);
            doc.setCinIngestProperties(props);
            // cin_ingestPropertyNames must always mirror cin_ingestProperties.keySet().
            doc.setCinIngestPropertyNames(new ArrayList<>(props.keySet()));
            // Records the owner, following the ingest convention where cin_read holds raw source
            // authorities and sys_acl holds the namespaced ones. No sys_acl is written: a feedback
            // document belongs to no content source, so there is no `_#_<sourceId>` suffix that could
            // be correct for it, and a document with no read ACE is invisible to the ACL-filtered
            // search path. Reaching it goes through this service's submitter predicate instead.
            doc.setCinRead(new ArrayList<>(List.of(submitter)));

            hxprService.createDocument(basePath, doc);
            log.info("Stored {} feedback {} (requestId={}, session={})",
                    request.getRating(), feedbackId, request.getRequestId(), request.getSessionId());
            return feedbackId;
        } catch (Exception e) {
            log.warn("Failed to store feedback (requestId={}): {}", request.getRequestId(), e.getMessage());
            return null;
        }
    }

    /**
     * Lists the calling user's own feedback, optionally filtered by rating.
     *
     * <p>The listing runs through the raw hxpr query API rather than the ACL-filtered search path, so
     * the ownership predicate below is the whole of the authorization: it is added to every query and
     * there is no argument a caller can pass to remove it. Authentication alone does not grant a view
     * of anyone else's questions and answers; see {@link #listAll} for the aggregate view.</p>
     */
    public List<FeedbackRecord> list(FeedbackRating rating, int limit) {
        if (!isEnabled()) {
            return List.of();
        }
        return query(rating, limit, securityContextService.getCurrentUsername());
    }

    /**
     * Lists every submitter's feedback, for the offline evaluation harness
     * ({@code cleval feedback import}), which needs the whole corpus rather than one operator's own
     * entries.
     *
     * <p>Restricted to the accounts named in {@code rag.feedback.operator-users}, which is empty by
     * default: a deployment that does not configure an operator has no aggregate view at all. Every
     * authenticated caller carries exactly {@code ROLE_USER}, since roles come from the source
     * repositories and neither exposes one, so the allow-list is the available way to say "operator"
     * rather than merely "authenticated".</p>
     *
     * @throws AccessDeniedException when the caller is not a configured operator
     */
    public List<FeedbackRecord> listAll(FeedbackRating rating, int limit) {
        if (!isEnabled()) {
            return List.of();
        }
        String username = securityContextService.getCurrentUsername();
        if (!isOperator(username)) {
            log.warn("Refusing the unscoped feedback listing to {}: not a configured operator", username);
            throw new AccessDeniedException("Listing all submitters' feedback requires an operator account");
        }
        log.info("Operator {} listed all submitters' feedback (rating={})", username, rating);
        return query(rating, limit, null);
    }

    /**
     * @param submitter the only submitter whose feedback may be returned, or {@code null} for the
     *                  operator-only aggregate view
     */
    private List<FeedbackRecord> query(FeedbackRating rating, int limit, String submitter) {
        int cappedLimit = Math.min(Math.max(limit, 1), 1000);
        // Select feedback docs by their rating property (equality predicates, as elsewhere in the
        // codebase). When no rating is requested, match either value rather than relying on an
        // IS NOT NULL predicate whose HXQL support is not assumed.
        String ratingClause = rating != null
                ? "cin_ingestProperties." + PROP_RATING + " = '" + rating.name() + "'"
                : "(cin_ingestProperties." + PROP_RATING + " = '" + FeedbackRating.UP.name() + "'"
                        + " OR cin_ingestProperties." + PROP_RATING + " = '" + FeedbackRating.DOWN.name() + "')";
        String hxql = "SELECT * FROM SysContent WHERE " + ratingClause;
        if (submitter != null) {
            // Escaped through AclFilterBuilder: a username is caller-controlled input reaching an HXQL
            // literal, and an unescaped apostrophe in one would end the literal.
            hxql += " AND cin_ingestProperties." + PROP_SUBMITTER
                    + " = '" + AclFilterBuilder.escapeLiteral(submitter) + "'";
        }

        try {
            HxprDocument.QueryResult result = hxprService.query(hxql, cappedLimit, 0);
            if (result == null || result.getDocuments() == null) {
                return List.of();
            }
            List<FeedbackRecord> records = new ArrayList<>();
            for (HxprDocument doc : result.getDocuments()) {
                FeedbackRecord record = toRecord(doc);
                if (record != null) {
                    records.add(record);
                }
            }
            return records;
        } catch (Exception e) {
            log.warn("Failed to list feedback: {}", e.getMessage());
            return List.of();
        }
    }

    private FeedbackRecord toRecord(HxprDocument doc) {
        Map<String, Object> props = doc.getCinIngestProperties();
        if (props == null || props.get(PROP_RATING) == null) {
            return null;
        }
        String sources = str(props.get(PROP_SOURCES));
        List<String> sourceNodeIds = (sources == null || sources.isBlank())
                ? null
                : Arrays.stream(sources.split(SOURCE_SEPARATOR)).map(String::trim).filter(s -> !s.isBlank()).toList();

        FeedbackRating rating;
        try {
            rating = FeedbackRating.valueOf(str(props.get(PROP_RATING)));
        } catch (IllegalArgumentException e) {
            return null;
        }

        return FeedbackRecord.builder()
                .feedbackId(doc.getSysName())
                .submitter(str(props.get(PROP_SUBMITTER)))
                .sessionId(str(props.get(PROP_SESSION_ID)))
                .requestId(str(props.get(PROP_REQUEST_ID)))
                .rating(rating)
                .comment(str(props.get(PROP_COMMENT)))
                .question(str(props.get(PROP_QUESTION)))
                .answer(str(props.get(PROP_ANSWER)))
                .sourceNodeIds(sourceNodeIds)
                .createdAt(str(props.get(PROP_CREATED_AT)))
                .build();
    }

    private boolean isOperator(String username) {
        List<String> operators = ragProperties.getFeedback().getOperatorUsers();
        if (operators == null || operators.isEmpty()) {
            return false;
        }
        // Case-insensitive because Alfresco authenticates usernames case-insensitively, so "Admin" and
        // "admin" are the same account and an operator list that distinguished them would surprise.
        return operators.stream()
                .filter(o -> o != null && !o.isBlank())
                .anyMatch(o -> o.trim().equalsIgnoreCase(username));
    }

    private static void put(Map<String, Object> props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.put(key, value);
        }
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }

    private String basePath() {
        String base = ragProperties.getFeedback().getBasePath();
        if (base == null || base.isBlank()) {
            base = "/_feedback";
        }
        return base.endsWith("/") && base.length() > 1 ? base.substring(0, base.length() - 1) : base;
    }
}
