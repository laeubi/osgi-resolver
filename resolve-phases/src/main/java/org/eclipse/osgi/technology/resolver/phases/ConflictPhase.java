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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.osgi.technology.resolver.candidates.Candidate;
import org.eclipse.osgi.technology.resolver.candidates.CandidateState;
import org.eclipse.osgi.technology.resolver.candidates.ResolveState;
import org.eclipse.osgi.technology.resolver.candidates.ResolverRequirement;
import org.eclipse.osgi.technology.resolver.candidates.ResolverResource;
import org.eclipse.osgi.technology.resolver.candidates.UsesViolation;
import org.eclipse.osgi.technology.resolver.conflicts.UsesConflictStrategy;
import org.eclipse.osgi.technology.resolver.index.Substitution;
import org.osgi.resource.Requirement;

/**
 * Hands the class space conflicts to the strategy, one cluster at a time.
 * A resource the class space rules report a violation for is the entry point of a
 * cluster: the resources whose class space can depend on the same choices.
 * Everything outside of it is consistent whatever the strategy decides, or has
 * an entry point of its own. The strategy sees only the cluster and is asked
 * again for the next entry point until none is left.
 */
public final class ConflictPhase implements ResolverPhase<Void> {

    private final UsesConflictStrategy strategy;
    private final ResolverGraph graph;
    private final ClassSpace classSpace;

    /**
     * @param strategy   decides the conflicts
     * @param graph      the graph after the selection
     * @param classSpace the class space of the graph
     */
    public ConflictPhase(UsesConflictStrategy strategy, ResolverGraph graph, ClassSpace classSpace) {
        this.strategy = strategy;
        this.graph = graph;
        this.classSpace = classSpace;
    }

    /**
     * Asks the strategy for every cluster and explains the substitutions of the
     * outcome.
     */
    @Override
    public Void run() {
        ResolverUsesSession whole = new ResolverUsesSession(graph, classSpace);
        boolean found;
        do {
            found = false;
            for (ResolverResource resource : new ArrayList<>(graph.getResources())) {
                if (resource.getState() != ResolveState.RESOLVED || !classSpace.violationOf(resource).isPresent()) {
                    continue;
                }
                found = true;
                enforce(whole, resource);
            }
        } while (found);
        classSpace.explainSubstitutions();
        return null;
    }

    private void enforce(ResolverUsesSession whole, ResolverResource entry) {
        Collection<ResolverResource> cluster = closureOf(whole, entry);
        List<ResolverResource> mandatory = new ArrayList<>();
        List<ResolverResource> optional = new ArrayList<>();
        rootsOf(cluster, mandatory, optional);
        strategy.enforceUses(new ResolverUsesSession(graph, classSpace, cluster, mandatory, optional));
        // a failure reaches whatever depends on the failed resource, also outside of the cluster
        whole.propagateFailures();
        if (entry.getState() == ResolveState.RESOLVED) {
            // a strategy has to decide its entry point, otherwise the search would never end
            Optional<UsesViolation> violation = classSpace.violationOf(entry);
            if (violation.isPresent()) {
                whole.fail(entry, violation.get());
                whole.propagateFailures();
            }
        }
    }

    /**
     * The resolved resources that are tied to the entry point through the
     * choices of their requirements, in the order of the graph. A choice ties a
     * resource to the choices its candidates use packages of and to the import
     * that decides whether an export of a candidate is discarded. Whoever can
     * see a choice is tied to it as well, and each resource added brings its own
     * choices. So the closure does not change if the strategy decides anything
     * inside of it, and no other closure shares a choice with it.
     */
    private Collection<ResolverResource> closureOf(ResolverUsesSession whole, ResolverResource entry) {
        Set<ResolverRequirement> choices = Collections
                .newSetFromMap(new IdentityHashMap<ResolverRequirement, Boolean>());
        Set<ResolverResource> members = Collections.newSetFromMap(new IdentityHashMap<ResolverResource, Boolean>());
        Deque<ResolverResource> todo = new ArrayDeque<>();
        members.add(entry);
        todo.add(entry);
        while (!todo.isEmpty()) {
            ResolverResource resource = todo.poll();
            List<ResolverRequirement> added = new ArrayList<>();
            for (ResolverResource part : withFragments(resource)) {
                for (ResolverRequirement requirement : part.getResolverRequirements()) {
                    collect(requirement, choices, added);
                }
            }
            for (ResolverResource affected : whole.affected(added)) {
                if (affected.getState() == ResolveState.RESOLVED && members.add(affected)) {
                    todo.add(affected);
                }
            }
        }
        Set<ResolverResource> ordered = new LinkedHashSet<>();
        for (ResolverResource resource : graph.getResources()) {
            if (members.contains(resource)) {
                ordered.add(resource);
            }
        }
        return ordered;
    }

    /**
     * What the cluster has to resolve: a resource that is a root of the graph
     * or wired to by a resource outside of the cluster, so it can not be left
     * out without the failure reaching the outside. It has to resolve if a
     * mandatory root needs it through requirements that are not optional.
     */
    private void rootsOf(Collection<ResolverResource> cluster, List<ResolverResource> mandatory,
            List<ResolverResource> optional) {
        Set<ResolverResource> needed = neededBy(graph.getMandatory());
        Set<ResolverResource> roots = Collections.newSetFromMap(new IdentityHashMap<ResolverResource, Boolean>());
        roots.addAll(graph.getMandatory());
        roots.addAll(graph.getOptional());
        for (ResolverResource resource : graph.getResources()) {
            if (resource.getState() == ResolveState.RESOLVED && !cluster.contains(resource)) {
                for (ResolverResource part : withFragments(resource)) {
                    for (ResolverRequirement requirement : part.getResolverRequirements()) {
                        for (Candidate candidate : requirement.getCandidates()) {
                            if (candidate.getState() == CandidateState.SELECTED) {
                                roots.add(candidate.getProvider());
                                // a capability a fragment adds to its host needs the fragment
                                roots.add(candidate.getCapability().getDeclaringResource());
                            }
                        }
                    }
                }
            }
        }
        for (ResolverResource resource : cluster) {
            if (roots.contains(resource)) {
                (needed.contains(resource) ? mandatory : optional).add(resource);
            }
        }
    }

    private static Set<ResolverResource> neededBy(Collection<ResolverResource> roots) {
        Set<ResolverResource> seen = Collections.newSetFromMap(new IdentityHashMap<ResolverResource, Boolean>());
        Deque<ResolverResource> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            ResolverResource resource = todo.poll();
            if (resource.getState() != ResolveState.RESOLVED || !seen.add(resource)) {
                continue;
            }
            for (ResolverResource part : withFragments(resource)) {
                for (ResolverRequirement requirement : part.getResolverRequirements()) {
                    if (requirement.isOptional()) {
                        continue;
                    }
                    for (Candidate candidate : requirement.getCandidates()) {
                        if (candidate.getState() == CandidateState.SELECTED) {
                            todo.add(candidate.getProvider());
                            todo.add(candidate.getCapability().getDeclaringResource());
                        }
                    }
                }
            }
        }
        return seen;
    }

    private static List<ResolverResource> withFragments(ResolverResource resource) {
        List<ResolverResource> parts = new ArrayList<>(resource.getAttachedFragments());
        parts.add(resource);
        return parts;
    }

    private void collect(ResolverRequirement requirement, Set<ResolverRequirement> choices,
            List<ResolverRequirement> added) {
        Deque<Requirement> todo = new ArrayDeque<>();
        todo.add(requirement);
        while (!todo.isEmpty()) {
            for (Requirement reachable : classSpace.getUsesGraph().reachable(todo.poll())) {
                ResolverRequirement choice = (ResolverRequirement) reachable;
                if (!choices.add(choice)) {
                    continue;
                }
                added.add(choice);
                for (Candidate candidate : choice.getCandidates()) {
                    Substitution substitution = classSpace.getSubstitutionIndex()
                            .getSubstitution(candidate.getCapability());
                    if (substitution != null) {
                        todo.add(substitution.getOverlappingImport());
                    }
                }
            }
        }
    }
}
