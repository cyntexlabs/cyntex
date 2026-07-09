package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.User;
import io.cyntex.spi.store.UserStore;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The zero-user bootstrap exception: on a brand-new server whose user table is still empty, create the
 * one first admin so an operator has an account to sign in with, then close the exception for good. The
 * channel is guarded twice — the call must come from the loopback interface, and the user table must be
 * empty — and the created admin's creation is audited like any other user creation.
 *
 * <p>The two guards are checked in that order, and the order is load-bearing. A non-loopback caller is
 * refused ({@code control.bootstrap-forbidden}) before the store is even consulted, so a remote caller
 * gets the same answer whether or not the server has been bootstrapped and learns nothing about its
 * state. Only a trusted loopback caller ever reaches the emptiness check, and a non-empty table refuses
 * it with the distinct {@code control.bootstrap-closed}. There is no separate "channel open" flag: once
 * the first admin is saved the table is no longer empty, so every later call — even from loopback —
 * fails the emptiness guard. The service composes the user store, the password hasher and the audit
 * gate, each bound at the assembly root; the only state it holds is a single-flight guard.
 *
 * <p>The emptiness check and the create are one critical section: without that, two concurrent loopback
 * callers could both pass the check on a still-empty table and each create a different first admin. A
 * single-flight guard serializes the whole check-and-create so exactly one wins and every later caller
 * sees a non-empty table. The guard is per-instance and so covers one node — enough for the single-node
 * first landing; a multi-node deployment would additionally need an atomic insert-if-absent in the store.
 */
public final class BootstrapService {

    /** The capability grade the first user is created with; the bootstrap account is always an admin. */
    private static final String ADMIN_ROLE = "admin";

    private final UserStore userStore;
    private final PasswordHasher passwordHasher;
    private final AuditGate auditGate;

    /** Serializes the check-and-create so concurrent callers cannot both create a first admin. */
    private final ReentrantLock singleFlight = new ReentrantLock();

    public BootstrapService(UserStore userStore, PasswordHasher passwordHasher, AuditGate auditGate) {
        this.userStore = Objects.requireNonNull(userStore, "userStore");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /**
     * Creates the first admin user through the bootstrap exception. Refuses a non-loopback {@code origin}
     * ({@code bootstrap-forbidden}) before consulting the store, and refuses a non-empty user table
     * ({@code bootstrap-closed}); on success the password is stored hashed (never raw) and the creation
     * is audited under {@code user.create} — a failed audit write refuses the creation (no audit, no
     * execute).
     */
    public void createFirstAdmin(CallerOrigin origin, String username, String password) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");

        // Origin first: a remote caller is turned away before the table is read, so the refusal it sees
        // is identical whether or not the server is still un-bootstrapped. This is outside the guard — it
        // reads no state, and refusing it need not queue behind an in-flight create.
        if (origin != CallerOrigin.LOOPBACK) {
            throw new CyntexException(ControlError.BOOTSTRAP_FORBIDDEN, Map.of(), null);
        }

        // The emptiness check and the create are one critical section, so two concurrent loopback callers
        // cannot both pass the check on an empty table: the loser sees the table already non-empty.
        singleFlight.lock();
        try {
            if (!userStore.isEmpty()) {
                throw new CyntexException(ControlError.BOOTSTRAP_CLOSED, Map.of(), null);
            }
            User admin = new User(username, passwordHasher.hash(password), ADMIN_ROLE);
            // Audit the creation under user.create; the gate writes the record first and refuses the save
            // if that write fails. The new admin is both the subject and the target of its own creation.
            auditGate.dispatch(ControlOperations.USER_CREATE, new AuditContext(username, username), () -> {
                userStore.save(admin);
                return null;
            });
        } finally {
            singleFlight.unlock();
        }
    }
}
