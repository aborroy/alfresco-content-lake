package org.hyland.contentlake.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the {@code sys_racl} permission predicate that scopes every hxpr query to what the calling
 * principal may read.
 *
 * <p>This is the single policy enforcement point for read access. The engine applies no ACL filter of
 * its own for our connection, because the service account it authenticates is an administrator, so the
 * predicate this class produces is the only thing standing between a caller and another caller's
 * documents. It therefore lives in one place, on purpose: one place to audit, one place to test, one
 * place that can be shown to a reviewer.</p>
 *
 * <h2>Principal encoding</h2>
 *
 * <p>Principals in {@code sys_racl} are namespaced per source, {@code <authority>_#_<sourceId>}, and
 * prefixed by kind: {@code u:} for users and {@code g:} for groups. The namespacing is what keeps
 * {@code g:sales_#_alfresco} from matching {@code g:sales_#_nuxeo}. Two repositories can each have a
 * {@code sales} group with different members, so stripping the suffix would merge two unrelated
 * populations and hand each the other's documents. Never strip it.</p>
 *
 * <p>{@code GROUP_EVERYONE} is the exception: it is a per-repository authority in the source systems
 * but a single un-namespaced {@link #EVERYONE_PRINCIPAL} value in the index, because a document
 * readable by everyone is readable regardless of which repository the caller came from.</p>
 *
 * <h2>Failing closed</h2>
 *
 * <p>When no source can be resolved for a caller, the predicate is {@link #unresolvedSourceClause()},
 * a source id that matches nothing. An empty predicate would match everything, so the absence of a
 * decision must never be encoded as the absence of a filter.</p>
 */
public final class AclFilterBuilder {

    /** The indexed ACL field on every hxpr document. */
    public static final String RACL_FIELD = "sys_racl";

    /** Un-namespaced principal carried by documents readable by any authenticated caller. */
    public static final String EVERYONE_PRINCIPAL = "__Everyone__";

    /** The source-system authority that maps to {@link #EVERYONE_PRINCIPAL}. */
    public static final String EVERYONE_AUTHORITY = "GROUP_EVERYONE";

    /** Alfresco's administrator group, which can read every document in an Alfresco source. */
    public static final String ALFRESCO_ADMINISTRATORS = "GROUP_ALFRESCO_ADMINISTRATORS";

    /**
     * Sentinel source id used when no permission source could be resolved. It matches no document, so
     * an unresolvable caller sees nothing rather than everything.
     */
    public static final String UNRESOLVED_SOURCE_ID = "__unresolved_permission_source__";

    /** Separator between an authority and the source id it belongs to. */
    public static final String SOURCE_ID_SEPARATOR = "_#_";

    /** Every ACL-scoped query starts here; the predicate is appended as a WHERE clause. */
    public static final String BASE_QUERY = "SELECT * FROM SysContent";

    private static final String SOURCE_ID_FIELD = "cin_sourceId";
    private static final String GROUP_AUTHORITY_PREFIX = "GROUP_";
    private static final String USER_RACL_PREFIX = "u:";
    private static final String GROUP_RACL_PREFIX = "g:";

    private AclFilterBuilder() {
    }

    /**
     * Escapes a value for inclusion in a single-quoted HXQL literal.
     *
     * <p>HXQL escapes with a backslash, so a backslash must be doubled before quotes are escaped or
     * the added backslash would itself be escaped. SQL-style quote doubling ({@code ''}) is <em>not</em>
     * accepted: the engine answers 400 on such a query, verified against a running engine.</p>
     */
    public static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** The authorities every caller has before any group lookup: themselves, and everyone. */
    public static List<String> defaultAuthorities(String username) {
        return List.of(username, EVERYONE_AUTHORITY);
    }

    /** {@code <authority>_#_<sourceId>}, the namespaced form stored in {@code sys_racl}. */
    public static String namespace(String authority, String sourceId) {
        return authority + SOURCE_ID_SEPARATOR + sourceId;
    }

    /** Matches one authority of one source, as {@code u:} for a user or {@code g:} for a group. */
    public static String authorityClause(String authority, String sourceId) {
        String prefix = authority.startsWith(GROUP_AUTHORITY_PREFIX) ? GROUP_RACL_PREFIX : USER_RACL_PREFIX;
        return raclEquals(prefix + namespace(authority, sourceId));
    }

    /** Matches documents readable by any authenticated caller. */
    public static String everyoneClause() {
        return raclEquals(EVERYONE_PRINCIPAL);
    }

    /** Matches every document of one source, bypassing {@code sys_racl} entirely. */
    public static String sourceIdClause(String qualifiedSourceId) {
        return SOURCE_ID_FIELD + " = '" + escapeLiteral(qualifiedSourceId) + "'";
    }

    /** Matches nothing. The predicate for a caller whose sources could not be resolved. */
    public static String unresolvedSourceClause() {
        return SOURCE_ID_FIELD + " = '" + UNRESOLVED_SOURCE_ID + "'";
    }

    /**
     * True when the caller may read a whole source without ACL filtering.
     *
     * <p>{@code bypassAllowed} is the caller's decision about whether the bypass applies at all, which
     * today means the source is an Alfresco source. Both conditions must hold, so the group name alone
     * grants nothing on a source that does not recognise it.</p>
     */
    public static boolean hasFullSourceAccess(Collection<String> authorities, boolean bypassAllowed) {
        return bypassAllowed && authorities != null && authorities.contains(ALFRESCO_ADMINISTRATORS);
    }

    /**
     * The predicate for one source: either the whole source when the caller has full access to it, or
     * the disjunction of the Everyone principal and each of the caller's authorities, namespaced to
     * that source.
     *
     * @param sourceId          bare source id, used to namespace authorities
     * @param qualifiedSourceId {@code <sourceType>:<sourceId>}, used only for the full-access clause
     * @param authorities       the caller's authorities on this source
     * @param bypassAllowed     whether a full-source-access bypass may apply to this source
     */
    public static String sourcePermissionClause(String sourceId, String qualifiedSourceId,
                                                List<String> authorities, boolean bypassAllowed) {
        if (hasFullSourceAccess(authorities, bypassAllowed)) {
            return sourceIdClause(qualifiedSourceId);
        }

        List<String> raclClauses = new ArrayList<>();
        raclClauses.add(everyoneClause());
        for (String authority : authorities) {
            // GROUP_EVERYONE is already covered by the un-namespaced Everyone principal above, and it
            // is never stored in its namespaced form.
            if (EVERYONE_AUTHORITY.equals(authority)) {
                continue;
            }
            raclClauses.add(authorityClause(authority, sourceId));
        }

        return "(" + String.join(" OR ", raclClauses) + ")";
    }

    /**
     * Assembles the final query from the per-source clauses and an optional caller-supplied predicate.
     *
     * <p>An empty {@code sourceClauses} yields {@link #unresolvedSourceClause()} rather than no
     * permission condition at all.</p>
     */
    public static String query(List<String> sourceClauses, String additionalFilter) {
        List<String> conditions = new ArrayList<>();
        if (sourceClauses == null || sourceClauses.isEmpty()) {
            conditions.add(unresolvedSourceClause());
        } else {
            conditions.add("(" + String.join(" OR ", sourceClauses) + ")");
        }
        if (additionalFilter != null && !additionalFilter.isBlank()) {
            conditions.add("(" + additionalFilter.trim() + ")");
        }
        return BASE_QUERY + " WHERE " + String.join(" AND ", conditions);
    }

    private static String raclEquals(String principal) {
        return RACL_FIELD + " = '" + escapeLiteral(principal) + "'";
    }
}
