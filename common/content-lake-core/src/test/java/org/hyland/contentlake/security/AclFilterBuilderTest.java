package org.hyland.contentlake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification for the ACL predicate.
 *
 * <p>Read access to every document depends on these strings, so this class is written as a
 * specification rather than as coverage: each test states one property the predicate must have, and
 * the failure of any one of them is a permission leak or a permission outage.</p>
 */
class AclFilterBuilderTest {

    private static final String ALFRESCO = "alfresco-source";
    private static final String NUXEO = "nuxeo-source";

    @Nested
    @DisplayName("literal escaping")
    class Escaping {

        @Test
        void quoteIsEscapedWithABackslash() {
            // Verified against a running engine: SQL-style '' is answered with HTTP 400, \' with 200.
            assertThat(AclFilterBuilder.escapeLiteral("o'brien")).isEqualTo("o\\'brien");
        }

        @Test
        void backslashIsDoubledBeforeQuotesAreEscaped() {
            // Doubling last would escape the backslash this method just added.
            assertThat(AclFilterBuilder.escapeLiteral("a\\b")).isEqualTo("a\\\\b");
            assertThat(AclFilterBuilder.escapeLiteral("a\\'b")).isEqualTo("a\\\\\\'b");
        }

        @Test
        void nullBecomesEmptyRatherThanTheStringNull() {
            assertThat(AclFilterBuilder.escapeLiteral(null)).isEmpty();
        }

        @Test
        void anApostropheInAUsernameCannotTerminateTheLiteral() {
            String clause = AclFilterBuilder.authorityClause("o'brien", ALFRESCO);
            assertThat(clause).isEqualTo("sys_racl = 'u:o\\'brien_#_alfresco-source'");
            // One opening and one closing quote, and nothing unquoted in between.
            assertThat(clause.chars().filter(c -> c == '\'').count()).isEqualTo(3);
            assertThat(clause).contains("\\'");
        }
    }

    @Nested
    @DisplayName("principal encoding")
    class Encoding {

        @Test
        void userAuthoritiesGetTheUserPrefix() {
            assertThat(AclFilterBuilder.authorityClause("alice", ALFRESCO))
                    .isEqualTo("sys_racl = 'u:alice_#_alfresco-source'");
        }

        @Test
        void groupAuthoritiesGetTheGroupPrefix() {
            assertThat(AclFilterBuilder.authorityClause("GROUP_sales", ALFRESCO))
                    .isEqualTo("sys_racl = 'g:GROUP_sales_#_alfresco-source'");
        }

        @Test
        void everyoneIsMatchedWithoutASourceSuffix() {
            // A document readable by everyone is readable whichever repository the caller came from.
            assertThat(AclFilterBuilder.everyoneClause()).isEqualTo("sys_racl = '__Everyone__'");
        }

        @Test
        void everyAuthorityCarriesItsSourceSuffix() {
            String clause = AclFilterBuilder.sourcePermissionClause(
                    ALFRESCO, "alfresco:" + ALFRESCO, List.of("alice", "GROUP_sales"), false);
            assertThat(clause).contains("_#_" + ALFRESCO);
            assertThat(clause).doesNotContain("'u:alice'");
            assertThat(clause).doesNotContain("'g:GROUP_sales'");
        }

        /**
         * The regression test that matters most. Two repositories can each have a "sales" group with
         * different members. If the suffix is ever stripped, every Nuxeo sales document becomes visible
         * to the Alfresco sales group and the other way round.
         */
        @Test
        void theSameGroupNameInTwoSourcesProducesTwoDifferentPrincipals() {
            String alfresco = AclFilterBuilder.authorityClause("GROUP_sales", "alfresco");
            String nuxeo = AclFilterBuilder.authorityClause("GROUP_sales", "nuxeo");

            assertThat(alfresco).isNotEqualTo(nuxeo);
            assertThat(alfresco).isEqualTo("sys_racl = 'g:GROUP_sales_#_alfresco'");
            assertThat(nuxeo).isEqualTo("sys_racl = 'g:GROUP_sales_#_nuxeo'");
        }
    }

    @Nested
    @DisplayName("per-source predicate")
    class SourceClause {

        @Test
        void combinesEveryoneWithEachAuthority() {
            String clause = AclFilterBuilder.sourcePermissionClause(
                    NUXEO, "nuxeo:" + NUXEO, List.of("bob", "GROUP_finance"), false);

            assertThat(clause).isEqualTo("(sys_racl = '__Everyone__'"
                    + " OR sys_racl = 'u:bob_#_nuxeo-source'"
                    + " OR sys_racl = 'g:GROUP_finance_#_nuxeo-source')");
        }

        @Test
        void groupEveryoneIsNotRepeatedInItsNamespacedForm() {
            String clause = AclFilterBuilder.sourcePermissionClause(
                    ALFRESCO, "alfresco:" + ALFRESCO,
                    AclFilterBuilder.defaultAuthorities("alice"), false);

            assertThat(clause).isEqualTo("(sys_racl = '__Everyone__'"
                    + " OR sys_racl = 'u:alice_#_alfresco-source')");
            assertThat(clause).doesNotContain("GROUP_EVERYONE");
        }

        @Test
        void administratorsBypassTheAclOnlyWhereTheBypassApplies() {
            List<String> admin = List.of("admin", AclFilterBuilder.ALFRESCO_ADMINISTRATORS);

            assertThat(AclFilterBuilder.sourcePermissionClause(ALFRESCO, "alfresco:" + ALFRESCO, admin, true))
                    .isEqualTo("cin_sourceId = 'alfresco:alfresco-source'");
            assertThat(AclFilterBuilder.sourcePermissionClause(NUXEO, "nuxeo:" + NUXEO, admin, false))
                    .contains("sys_racl = 'u:admin_#_nuxeo-source'");
        }

        @Test
        void theAdministratorGroupNameAloneGrantsNothing() {
            assertThat(AclFilterBuilder.hasFullSourceAccess(
                    List.of(AclFilterBuilder.ALFRESCO_ADMINISTRATORS), false)).isFalse();
            assertThat(AclFilterBuilder.hasFullSourceAccess(List.of("alice"), true)).isFalse();
            assertThat(AclFilterBuilder.hasFullSourceAccess(null, true)).isFalse();
        }
    }

    @Nested
    @DisplayName("assembled query")
    class Query {

        @Test
        void wrapsTheSourceClausesInADisjunction() {
            String hxql = AclFilterBuilder.query(List.of("(a)", "(b)"), null);
            assertThat(hxql).isEqualTo("SELECT * FROM SysContent WHERE ((a) OR (b))");
        }

        @Test
        void andsTheCallersOwnFilterAsASeparateGroup() {
            // Parenthesised, so an OR inside the caller's filter cannot widen the permission clause.
            String hxql = AclFilterBuilder.query(List.of("(a)"), "x = 1 OR y = 2");
            assertThat(hxql).isEqualTo("SELECT * FROM SysContent WHERE ((a)) AND (x = 1 OR y = 2)");
        }

        @Test
        void blankCallerFilterIsIgnored() {
            assertThat(AclFilterBuilder.query(List.of("(a)"), "   "))
                    .isEqualTo("SELECT * FROM SysContent WHERE ((a))");
        }

        @Test
        void noResolvableSourceMatchesNothingRatherThanEverything() {
            String hxql = AclFilterBuilder.query(List.of(), null);

            assertThat(hxql).isEqualTo(
                    "SELECT * FROM SysContent WHERE cin_sourceId = '__unresolved_permission_source__'");
            assertThat(hxql).doesNotContain("WHERE ()");
        }

        @Test
        void noResolvableSourceStillAppliesTheCallersFilter() {
            assertThat(AclFilterBuilder.query(null, "cin_id = '1'")).isEqualTo(
                    "SELECT * FROM SysContent WHERE cin_sourceId = '__unresolved_permission_source__'"
                            + " AND (cin_id = '1')");
        }
    }
}
