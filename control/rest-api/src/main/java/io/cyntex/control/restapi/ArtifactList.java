package io.cyntex.control.restapi;

import io.cyntex.control.core.StoredArtifact;

import java.util.List;

/**
 * The artifact-list response body: the stored artifacts a list read returns, each as its canonical form
 * from the truth layer. A wrapper object rather than a bare array so the response can grow fields
 * (paging, a total) without changing the wire shape.
 */
public record ArtifactList(List<StoredArtifact> artifacts) {
}
