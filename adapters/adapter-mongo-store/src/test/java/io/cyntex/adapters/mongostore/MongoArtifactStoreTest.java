package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The artifact-document codec is the round-trip core of the artifact truth layer: a resource is
 * stored in canonical form and reconstructed from it on read. These witness the mapping
 * deterministically, without a Mongo server, across every resource kind and a spread of
 * fidelity-sensitive shapes (metadata, a literal js block, field rules, nested storage). A real
 * Mongo round-trip is exercised by {@code MongoArtifactStoreIT} (skipped where Docker is absent).
 */
class MongoArtifactStoreTest {

    private static final CanonicalWriter WRITER = new CanonicalWriter();
    private static final DslParser PARSER = new DslParser();

    private static final String SOURCE = """
            version: cyntex/v1
            kind: source
            id: orders
            connector: mysql
            config:
              host: localhost
              port: 3306
            tables:
              - orders
              - customers
            """;

    private static final String PIPELINE = """
            version: cyntex/v1
            kind: pipeline
            id: orders_sync
            metadata:
              labels:
                team: data
              description: keep the warehouse in sync
            source: orders
            transforms:
              - id: clean
                type: js
                from: orders
                script: |
                  return record;
            settings:
              read_mode: cdc_only
            """;

    private static final String TRANSFORM = """
            version: cyntex/v1
            kind: transform
            id: normalize
            type: map
            fields:
              full_name: $name
              internal: false
              status: active
            """;

    private static final String VIEW = """
            version: cyntex/v1
            kind: view
            id: customer_view
            primary_key: id
            storage:
              warm:
                collection: customers
                indexes:
                  - email
            """;

    private static final String SERVE = """
            version: cyntex/v1
            kind: serve
            id: orders_api
            sync:
              - id: to_dw
                source: orders
                write_mode: append
            """;

    private record Fixture(String label, String id, String kind, String raw) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("source", "orders", "source", SOURCE),
            new Fixture("pipeline", "orders_sync", "pipeline", PIPELINE),
            new Fixture("transform", "normalize", "transform", TRANSFORM),
            new Fixture("view", "customer_view", "view", VIEW),
            new Fixture("serve", "orders_api", "serve", SERVE));

    @Test
    void documentCarriesIdKindAndCanonicalBodyForEveryKind() {
        for (Fixture fixture : FIXTURES) {
            String canonical = canonical(fixture.raw());
            Document document = MongoArtifactStore.toDocument(PARSER.parse(canonical));

            assertThat(document.getString("_id")).as("%s _id", fixture.label()).isEqualTo(fixture.id());
            assertThat(document.getString("kind")).as("%s kind", fixture.label()).isEqualTo(fixture.kind());
            assertThat(document.getString("canonical")).as("%s body", fixture.label()).isEqualTo(canonical);
        }
    }

    @Test
    void roundTripReconstructsTheSameCanonicalFormForEveryKind() {
        for (Fixture fixture : FIXTURES) {
            String canonical = canonical(fixture.raw());
            Resource reconstructed =
                    MongoArtifactStore.toResource(MongoArtifactStore.toDocument(PARSER.parse(canonical)));

            assertThat(WRITER.write(reconstructed))
                    .as("a stored %s reconstructs to the same canonical form", fixture.label())
                    .isEqualTo(canonical);
        }
    }

    @Test
    void toResourceOnAMissingCanonicalBodyIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders").append("kind", "source");

        Throwable thrown = catchThrowable(() -> MongoArtifactStore.toResource(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders");
    }

    @Test
    void toResourceOnACorruptCanonicalBodyIsDocumentUnreadable() {
        // A stored body that no longer parses (corruption, or a newer grammar) surfaces as a storage io
        // diagnostic — not a leaked authoring (dsl.*) code for a document the user never authored.
        Document corrupt =
                new Document("_id", "orders").append("kind", "source").append("canonical", "not: [valid");

        Throwable thrown = catchThrowable(() -> MongoArtifactStore.toResource(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders");
    }

    /** Normalizes raw YAML to its canonical form (the form the store persists). */
    private static String canonical(String raw) {
        return WRITER.write(PARSER.parse(raw));
    }
}
