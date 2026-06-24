package io.cyntex.core.model;

import java.util.Map;

/**
 * A top-level {@code .cyn.yml} resource. One document holds exactly one resource;
 * identity is the top-level {@code id} (ADR-0016 §2, F6).
 *
 * <p>The model represents the {@code cyntex/v1} grammar only — {@code version} is a
 * constant of the contract, not a field of the model.
 */
@Doc("A top-level cyntex/v1 resource; one document holds exactly one of source, pipeline, transform, view or serve.")
public sealed interface Resource
        permits SourceResource, PipelineResource, TransformResource, ViewResource, ServeResource {

    String VERSION = "cyntex/v1";

    /** Resource kind discriminator as it appears in YAML ({@code kind:}). */
    String kind();

    /** Top-level id — unique per workspace across all kinds (F8). */
    String id();

    /** Optional annotation block (labels / description); never identity. */
    Metadata metadata();

    /** Experimental escape hatch (§11.6); exempt from the v1 freeze. */
    Map<String, Object> experimental();
}
