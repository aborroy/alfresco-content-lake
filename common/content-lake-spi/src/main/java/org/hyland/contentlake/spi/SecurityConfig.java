package org.hyland.contentlake.spi;

import java.util.List;

/**
 * Structured, vendor-neutral security descriptor for a {@link SourceNode}.
 *
 * <p>Wraps the node's {@link PermissionRule}s together with whether the node inherits ACLs from its
 * parent, mirroring the Open Ingestion Standard (OIS) {@code SecurityConfig} shape. This is carried
 * alongside the flat {@code readPrincipals} / {@code denyPrincipals} sets that the hxpr pipeline uses;
 * it exists so future output connectors can represent ACL inheritance and group-level rules that the
 * flat sets cannot.</p>
 *
 * @param inheritanceEnabled {@code true} when the node inherits permissions from its parent container
 * @param permissions        the explicit access rules declared on this node
 */
public record SecurityConfig(boolean inheritanceEnabled, List<PermissionRule> permissions) {}
