package io.cyntex.e2e;

import java.util.List;

/**
 * One declarative test specification. This is a frozen authoring surface: humans and AI write it
 * by hand, so it evolves by adding facets and never by breaking what already parses.
 *
 * <p>{@code pipeline} references a product {@code .cyn.yml} by path and is read with the product's
 * own parser, so a specification cannot drift from the DSL it exercises.
 *
 * @param setup the connection-provisioning facet: the bootstrap a real endpoint needs before a
 *     pipeline can reference it at all
 * @param seed the initial data laid down before the first step runs
 */
public record Envelope(
        String name, Tier tier, Setup setup, String pipeline, List<Seed> seed, List<Step> steps) {

    public Envelope {
        seed = List.copyOf(seed);
        steps = List.copyOf(steps);
    }
}
