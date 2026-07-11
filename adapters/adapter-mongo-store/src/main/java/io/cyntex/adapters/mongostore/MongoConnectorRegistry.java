package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import org.bson.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB connector distribution registry: stores each registered connector artifact in a GridFS
 * bucket keyed by the content hash of its bytes (the GridFS filename), carrying its identity — connector
 * id, declared PDK API version, and registration source — in the file's metadata.
 *
 * <p>Registration is content-hash idempotent: the hash is computed from the bytes, and a re-register of
 * bytes whose hash is already stored is found here and returns a no-op outcome, so a startup seed sweep
 * and an explicit runtime register share one path without ever storing a second copy. Keying identity
 * and bytes in one GridFS file means a registration and its bytes are never half-present. Driver IO
 * failures are translated into coded io diagnostics and a file whose metadata cannot be reconstructed is
 * surfaced as {@code io.document-unreadable}, so no driver type escapes the module (rule R3).
 */
public final class MongoConnectorRegistry implements ConnectorRegistry {

    private final GridFSBucket artifacts;

    public MongoConnectorRegistry(GridFSBucket artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public RegistrationOutcome register(
            String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
        Objects.requireNonNull(connectorId, "connectorId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(artifact, "artifact");
        String contentHash = sha256Hex(artifact);
        return StoreIo.call(() -> {
            // register-if-absent: the bytes are stored under their content hash (the filename), so an
            // already-registered artifact is found here and the call is a no-op returning what is stored.
            GridFSFile existing = artifacts.find(Filters.eq("filename", contentHash)).first();
            if (existing != null) {
                return new RegistrationOutcome(toRegistration(contentHash, existing.getMetadata()), false);
            }
            GridFSUploadOptions options = new GridFSUploadOptions().metadata(metadata(connectorId, pdkApiVersion, source));
            artifacts.uploadFromStream(contentHash, new ByteArrayInputStream(artifact), options);
            return new RegistrationOutcome(
                    new ConnectorRegistration(connectorId, contentHash, pdkApiVersion, source), true);
        });
    }

    @Override
    public List<ConnectorRegistration> list() {
        return StoreIo.call(() -> {
            List<ConnectorRegistration> all = new ArrayList<>();
            try (MongoCursor<GridFSFile> cursor = artifacts.find().iterator()) {
                while (cursor.hasNext()) {
                    GridFSFile file = cursor.next();
                    all.add(toRegistration(file.getFilename(), file.getMetadata()));
                }
            }
            return all;
        });
    }

    @Override
    public Optional<byte[]> artifact(String contentHash) {
        Objects.requireNonNull(contentHash, "contentHash");
        return StoreIo.call(() -> {
            GridFSFile file = artifacts.find(Filters.eq("filename", contentHash)).first();
            if (file == null) {
                return Optional.empty();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            artifacts.downloadToStream(file.getObjectId(), bytes);
            return Optional.of(bytes.toByteArray());
        });
    }

    /** The identity carried in a stored artifact's GridFS metadata (the content hash is the filename). */
    static Document metadata(String connectorId, String pdkApiVersion, RegistrationSource source) {
        return new Document("connectorId", connectorId)
                .append("pdkApiVersion", pdkApiVersion)
                .append("source", source.name());
    }

    /** Reconstructs a registration from a content hash and the stored metadata, or fails coded if unreadable. */
    static ConnectorRegistration toRegistration(String contentHash, Document metadata) {
        if (metadata == null) {
            throw unreadable(contentHash);
        }
        String connectorId = metadata.getString("connectorId");
        String sourceName = metadata.getString("source");
        if (connectorId == null || sourceName == null) {
            // A stored artifact missing its identity is registry corruption, surfaced as a coded io
            // diagnostic rather than a bare null-argument crash while reconstructing.
            throw unreadable(contentHash);
        }
        RegistrationSource source;
        try {
            source = RegistrationSource.valueOf(sourceName);
        } catch (IllegalArgumentException e) {
            // A stored source that is not a known enum constant is corruption, not silently coerced.
            throw unreadable(contentHash);
        }
        return new ConnectorRegistration(connectorId, contentHash, metadata.getString("pdkApiVersion"), source);
    }

    /** Lower-hex SHA-256 of the artifact bytes: the content-addressed registration key. */
    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; its absence is a broken runtime, not a user error.
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static CyntexException unreadable(String contentHash) {
        return new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(contentHash)), null);
    }
}
