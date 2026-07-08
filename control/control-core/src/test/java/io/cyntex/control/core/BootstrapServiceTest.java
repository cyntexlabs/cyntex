package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.User;
import io.cyntex.spi.store.UserStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The zero-user localhost bootstrap exception: a brand-new server (its user table empty) accepts, from
 * a loopback caller only, the one-time creation of the first admin user; once any user exists the
 * channel closes. Two guards are load-bearing and are asserted here, not just their happy path:
 *
 * <ul>
 *   <li><b>Origin before emptiness.</b> A non-loopback caller is refused before the store is even
 *       consulted, so a remote caller learns nothing about whether the server is still un-bootstrapped
 *       — the two refusals carry distinct codes, and the remote one is returned regardless of table
 *       state.
 *   <li><b>No audit, no execute.</b> Creating the first admin is an audited write; if its audit record
 *       cannot be written the admin is not created (the standard audit-gate guarantee, reused rather
 *       than re-implemented).
 * </ul>
 *
 * <p>All collaborators are in-memory fakes: the user store records saves and reports emptiness, the
 * hasher is a deterministic stand-in for the adaptive hash, and the audit store either records or
 * fails on demand.
 */
class BootstrapServiceTest {

    private static final Instant FIXED = Instant.parse("2026-07-08T09:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void aRemoteCallerIsRefusedAndNoUserIsCreated() {
        FakeUserStore users = new FakeUserStore();
        RecordingAuditStore audit = new RecordingAuditStore();
        BootstrapService bootstrap = new BootstrapService(users, new FakeHasher(), new AuditGate(audit, FIXED_CLOCK));

        CyntexException thrown = catchThrowableOfType(CyntexException.class,
                () -> bootstrap.createFirstAdmin(CallerOrigin.REMOTE, "alice", "s3cret"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code().code()).isEqualTo("control.bootstrap-forbidden");
        // The refusal must echo nothing back: a payload here could encode table state, which is exactly
        // what checking origin before emptiness exists to withhold from a remote caller.
        assertThat(thrown.args()).as("the remote refusal carries no state-revealing payload").isEmpty();
        assertThat(users.saved).as("a refused bootstrap creates no user").isEmpty();
        assertThat(audit.records).as("a refused bootstrap leaves no audit record").isEmpty();
    }

    @Test
    void theOriginIsCheckedBeforeEmptinessSoARemoteCallerLearnsNothingAboutTheTable() {
        // The store already has a user: were emptiness checked first, this would surface as "closed".
        // The remote caller must instead be refused as "forbidden" — the same answer it gets on an empty
        // store — so it cannot tell an un-bootstrapped server from a bootstrapped one.
        FakeUserStore users = new FakeUserStore();
        users.save(new User("existing", "hash:pw", "admin"));
        BootstrapService bootstrap = new BootstrapService(
                users, new FakeHasher(), new AuditGate(new RecordingAuditStore(), FIXED_CLOCK));

        CyntexException thrown = catchThrowableOfType(CyntexException.class,
                () -> bootstrap.createFirstAdmin(CallerOrigin.REMOTE, "alice", "s3cret"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code().code())
                .as("origin is rejected before the table is consulted, so no table state leaks")
                .isEqualTo("control.bootstrap-forbidden");
        assertThat(thrown.args())
                .as("the refusal echoes nothing back, so it cannot carry table state either")
                .isEmpty();
    }

    @Test
    void aNonEmptyStoreClosesTheChannelEvenFromLoopback() {
        FakeUserStore users = new FakeUserStore();
        users.save(new User("existing", "hash:pw", "admin"));
        BootstrapService bootstrap = new BootstrapService(
                users, new FakeHasher(), new AuditGate(new RecordingAuditStore(), FIXED_CLOCK));

        CyntexException thrown = catchThrowableOfType(CyntexException.class,
                () -> bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code().code()).isEqualTo("control.bootstrap-closed");
        assertThat(thrown.args()).as("the closed-channel refusal carries no payload").isEmpty();
        assertThat(users.saved).as("the channel is closed; no second user is written").hasSize(1);
    }

    @Test
    void aLoopbackCallerOnAnEmptyStoreCreatesTheFirstAdmin() {
        FakeUserStore users = new FakeUserStore();
        FakeHasher hasher = new FakeHasher();
        BootstrapService bootstrap = new BootstrapService(
                users, hasher, new AuditGate(new RecordingAuditStore(), FIXED_CLOCK));

        bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret");

        Optional<User> created = users.find("alice");
        assertThat(created).isPresent();
        assertThat(created.get().role()).as("the first user is created with admin capability").isEqualTo("admin");
    }

    @Test
    void theFirstAdminPasswordIsStoredHashedNeverRaw() {
        FakeUserStore users = new FakeUserStore();
        FakeHasher hasher = new FakeHasher();
        BootstrapService bootstrap = new BootstrapService(
                users, hasher, new AuditGate(new RecordingAuditStore(), FIXED_CLOCK));

        bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret");

        String stored = users.find("alice").orElseThrow().passwordHash();
        assertThat(stored).isEqualTo(hasher.hash("s3cret"));
        assertThat(stored).as("the raw password is never persisted").isNotEqualTo("s3cret");
    }

    @Test
    void theFirstAdminCreationIsAudited() {
        FakeUserStore users = new FakeUserStore();
        RecordingAuditStore audit = new RecordingAuditStore();
        BootstrapService bootstrap = new BootstrapService(users, new FakeHasher(), new AuditGate(audit, FIXED_CLOCK));

        bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret");

        assertThat(audit.records).hasSize(1);
        AuditRecord record = audit.records.get(0);
        assertThat(record.operationId()).isEqualTo("user.create");
        assertThat(record.principal()).isEqualTo("alice");
        assertThat(record.resourceId()).isEqualTo("alice");
        assertThat(record.timestamp()).isEqualTo(FIXED);
    }

    @Test
    void theChannelClosesAfterTheFirstAdminIsCreated() {
        FakeUserStore users = new FakeUserStore();
        BootstrapService bootstrap = new BootstrapService(
                users, new FakeHasher(), new AuditGate(new RecordingAuditStore(), FIXED_CLOCK));

        bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret");

        // Same loopback caller, immediately after: the exception has closed because a user now exists.
        CyntexException second = catchThrowableOfType(CyntexException.class,
                () -> bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "bob", "another"));
        assertThat(second).isNotNull();
        assertThat(second.code().code()).isEqualTo("control.bootstrap-closed");
        assertThat(users.find("bob")).as("no second admin is created once the channel has closed").isEmpty();
    }

    @Test
    void aFailedAuditRefusesTheCreationSoNoAdminIsWritten() {
        FakeUserStore users = new FakeUserStore();
        BootstrapService bootstrap = new BootstrapService(
                users, new FakeHasher(), new AuditGate(new FailingAuditStore(), FIXED_CLOCK));

        CyntexException thrown = catchThrowableOfType(CyntexException.class,
                () -> bootstrap.createFirstAdmin(CallerOrigin.LOOPBACK, "alice", "s3cret"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code().code())
                .as("no audit, no execute: a failed audit write refuses the creation")
                .isEqualTo("control.audit-blocked");
        assertThat(users.saved).as("the admin is not created when its audit record could not be written").isEmpty();
    }

    /** An in-memory user store keyed by username that also reports emptiness for the bootstrap guard. */
    private static final class FakeUserStore implements UserStore {
        private final Map<String, User> saved = new HashMap<>();

        @Override
        public Optional<User> find(String username) {
            return Optional.ofNullable(saved.get(username));
        }

        @Override
        public void save(User user) {
            saved.put(user.username(), user);
        }

        @Override
        public boolean isEmpty() {
            return saved.isEmpty();
        }
    }

    /** A deterministic stand-in for the adaptive password hash. */
    private static final class FakeHasher implements PasswordHasher {
        @Override
        public String hash(String raw) {
            return "hash:" + raw;
        }

        @Override
        public boolean matches(String raw, String storedHash) {
            return storedHash.equals(hash(raw));
        }
    }

    /** An audit store that captures every record written through it. */
    private static final class RecordingAuditStore implements AuditStore {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }

    /** An audit store that always fails, standing in for an unavailable audit backend. */
    private static final class FailingAuditStore implements AuditStore {
        @Override
        public void record(AuditRecord record) {
            throw new IllegalStateException("audit backend down");
        }
    }
}
