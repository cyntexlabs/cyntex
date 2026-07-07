package io.cyntex.control.core;

import java.util.List;
import java.util.Map;

/**
 * The canonical set of control operations, declared as code constants (no classpath or reflection
 * scanning). This is the single source of truth from which every face derives its surface, and the
 * seed an {@link OperationRegistry} is built over.
 *
 * <p>The first landing opens only the subset it needs, and only on the CLI face at {@code POC} — no
 * operation carries an {@code MCP} or {@code REST} exposure key yet, so those faces derive empty.
 * Later slices append their own domains (task, connector, cluster, runtime read) here, so every face
 * grows the new verbs in lockstep rather than each face maintaining its own hand-written list.
 *
 * <p>The audit flag marks the operations that mutate persisted control-plane state (an artifact, a
 * user, a token) and therefore leave a record; read and list operations and the read-only connection
 * probe carry no audit flag.
 */
public final class ControlOperations {

    private static final Map<Frontend, Maturity> CLI_POC = Map.of(Frontend.CLI, Maturity.POC);

    // artifact domain
    public static final Operation ARTIFACT_APPLY = new Operation("artifact.apply", Scope.WRITE, true, null, CLI_POC);
    public static final Operation ARTIFACT_GET = new Operation("artifact.get", Scope.READ, false, null, CLI_POC);
    public static final Operation ARTIFACT_LIST = new Operation("artifact.list", Scope.READ, false, null, CLI_POC);

    // connection domain: runs an external probe and persists its result for later query and display, so
    // it mutates persisted state (a write) and is audited. The probe and the result-store shape land in a
    // later slice; this only reserves the contract.
    public static final Operation CONNECTION_TEST = new Operation("connection.test", Scope.WRITE, true, null, CLI_POC);

    // security domain: all admin-scoped. The mutating ones are audited; the list queries are not.
    public static final Operation USER_CREATE = new Operation("user.create", Scope.ADMIN, true, null, CLI_POC);
    public static final Operation USER_PASSWD = new Operation("user.passwd", Scope.ADMIN, true, null, CLI_POC);
    public static final Operation USER_LIST = new Operation("user.list", Scope.ADMIN, false, null, CLI_POC);
    public static final Operation TOKEN_CREATE = new Operation("token.create", Scope.ADMIN, true, null, CLI_POC);
    public static final Operation TOKEN_REVOKE = new Operation("token.revoke", Scope.ADMIN, true, null, CLI_POC);
    public static final Operation TOKEN_LIST = new Operation("token.list", Scope.ADMIN, false, null, CLI_POC);

    private static final List<Operation> ALL = List.of(
            ARTIFACT_APPLY,
            ARTIFACT_GET,
            ARTIFACT_LIST,
            CONNECTION_TEST,
            USER_CREATE,
            USER_PASSWD,
            USER_LIST,
            TOKEN_CREATE,
            TOKEN_REVOKE,
            TOKEN_LIST);

    private ControlOperations() {
    }

    /** All canonical operations, in a stable declaration order. */
    public static List<Operation> all() {
        return ALL;
    }

    /** A registry built over the canonical operation set. */
    public static OperationRegistry registry() {
        return OperationRegistry.of(ALL);
    }
}
