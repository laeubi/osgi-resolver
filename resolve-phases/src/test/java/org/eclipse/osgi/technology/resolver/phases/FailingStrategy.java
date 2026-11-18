/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.osgi.technology.resolver.phases;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.osgi.technology.resolver.candidates.ResolveState;
import org.eclipse.osgi.technology.resolver.candidates.ResolverResource;
import org.eclipse.osgi.technology.resolver.conflicts.UsesConflictStrategy;
import org.eclipse.osgi.technology.resolver.conflicts.UsesSession;

/**
 * A strategy that repairs nothing, a resource with an inconsistent class space
 * fails. Counts how often it was asked and records what it was shown.
 */
final class FailingStrategy implements UsesConflictStrategy {

    int calls;
    final List<Set<String>> scopes = new ArrayList<>();

    @Override
    public void enforceUses(UsesSession session) {
        calls++;
        Set<String> scope = new HashSet<>();
        for (ResolverResource resource : session.getResources()) {
            scope.add(resource.getName());
        }
        scopes.add(scope);
        for (ResolverResource resource : session.getResources()) {
            if (resource.getState() == ResolveState.RESOLVED) {
                session.violationOf(resource).ifPresent(violation -> session.fail(resource, violation));
            }
        }
        session.propagateFailures();
    }
}
