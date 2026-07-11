package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.RegistrationSource;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The content hash and the metadata codec are the mapping core of the connector registry: the artifact's
 * content hash is the registration key (the GridFS filename) and its identity travels in the file's
 * metadata. These witness both deterministically, without a Mongo server: the hash is the published
 * lower-hex SHA-256 of the bytes, the metadata round-trips, and metadata that cannot be reconstructed
 * surfaces as a coded {@code io.document-unreadable} diagnostic. The register-if-absent behaviour and the
 * GridFS round-trip are exercised by {@code MongoConnectorRegistryIT} (skipped where Docker is absent).
 */
class MongoConnectorRegistryTest {

    @Test
    void contentHashIsTheLowerHexSha256OfTheBytes() {
        // The published SHA-256 vector for "abc": pins algorithm + UTF-8 encoding + lower-hex, so a
        // drift in how the registration key is derived reddens here rather than silently re-keying.
        String hash = MongoConnectorRegistry.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void metadataRoundTripsToTheSameRegistration() {
        Document metadata = MongoConnectorRegistry.metadata("mysql", "1.3.5", RegistrationSource.REGISTER);

        ConnectorRegistration registration = MongoConnectorRegistry.toRegistration("abc123", metadata);

        assertThat(registration).isEqualTo(
                new ConnectorRegistration("mysql", "abc123", "1.3.5", RegistrationSource.REGISTER));
    }

    @Test
    void anAbsentApiVersionRoundTripsAsNull() {
        Document metadata = MongoConnectorRegistry.metadata("mysql", null, RegistrationSource.SEED);

        assertThat(MongoConnectorRegistry.toRegistration("abc123", metadata).pdkApiVersion()).isNull();
    }

    @Test
    void toRegistrationOnAbsentMetadataIsDocumentUnreadable() {
        Throwable thrown = catchThrowable(() -> MongoConnectorRegistry.toRegistration("abc123", null));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "abc123");
    }

    @Test
    void toRegistrationMissingConnectorIdIsDocumentUnreadable() {
        Document metadata = new Document("source", "SEED");

        Throwable thrown = catchThrowable(() -> MongoConnectorRegistry.toRegistration("abc123", metadata));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toRegistrationWithAnUnknownSourceIsDocumentUnreadable() {
        Document metadata = new Document("connectorId", "mysql").append("source", "SIDELOADED");

        Throwable thrown = catchThrowable(() -> MongoConnectorRegistry.toRegistration("abc123", metadata));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
