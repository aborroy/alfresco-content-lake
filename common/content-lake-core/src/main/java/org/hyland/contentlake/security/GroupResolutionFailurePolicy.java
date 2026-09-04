package org.hyland.contentlake.security;

/**
 * What to do when the group membership of a caller cannot be resolved against a source repository.
 *
 * <p>Group membership decides which {@code sys_racl} principals a caller matches, so a failed lookup is
 * missing authorization input, not a missing optimisation. The two answers available are to narrow the
 * caller's access to nothing on that source, or to carry on with the authorities that need no lookup.
 * Both are legitimate; only one of them is safe by default.</p>
 */
public enum GroupResolutionFailurePolicy {

    /**
     * Drop the source from the permission filter, so the caller sees nothing from it until the lookup
     * works again. The default: a directory outage narrows results rather than widening them.
     */
    FAIL_CLOSED,

    /**
     * Carry on with the authorities that need no lookup, the caller's own name and
     * {@code GROUP_EVERYONE}. The caller keeps access to their own documents and to public ones, and
     * silently loses every group-granted document until the lookup works again.
     */
    DEGRADE;

    /**
     * Parses a configured value, accepting {@code fail-closed} and {@code degrade} in any case and with
     * either a hyphen or an underscore. Anything unrecognised, including null and blank, yields
     * {@link #FAIL_CLOSED}: a typo in configuration must not widen access.
     */
    public static GroupResolutionFailurePolicy parse(String value) {
        if (value == null) {
            return FAIL_CLOSED;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        return DEGRADE.name().equals(normalized) ? DEGRADE : FAIL_CLOSED;
    }
}
