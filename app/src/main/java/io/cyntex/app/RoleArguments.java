package io.cyntex.app;

import io.cyntex.core.common.CyntexException;

import java.util.Map;

/**
 * Resolves the process role from the command line. Cyntex ships a single binary that can run a
 * subset of planes selected by {@code --role}; at L1 the planes are not separable, so the only
 * supported role is {@code all} (the whole platform in one JVM). An absent {@code --role} defaults
 * to {@code all}; any other value is a user-facing, coded error rather than a silent fallback.
 *
 * <p>Pure and framework-free so it runs before the application context starts — an unsupported role
 * must fail fast, before any service component is brought up — and so it is directly unit-testable.
 */
final class RoleArguments {

    /** The only role L1 supports: the whole platform in a single process. */
    static final String ALL = "all";

    private static final String FLAG = "--role";

    private RoleArguments() {
    }

    /**
     * Resolves the role from {@code args}, accepting both {@code --role=VALUE} and the separated
     * {@code --role VALUE} form, last occurrence winning. Returns {@link #ALL} when no role is
     * given. Throws a coded {@link CyntexException} ({@code role.unsupported}) for any other value.
     * Non-role arguments are ignored, so framework arguments pass through untouched.
     */
    static String parse(String[] args) {
        String role = ALL;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals(FLAG)) {
                role = i + 1 < args.length ? args[i + 1] : "";
            } else if (arg.startsWith(FLAG + "=")) {
                role = arg.substring(FLAG.length() + 1);
            }
        }
        if (!ALL.equals(role)) {
            throw new CyntexException(RoleError.UNSUPPORTED, Map.of("role", role), null);
        }
        return role;
    }
}
