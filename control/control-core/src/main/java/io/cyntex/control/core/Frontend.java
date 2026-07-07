package io.cyntex.control.core;

/**
 * A protocol face that projects the operation registry into its own operation surface.
 *
 * <p>A face may only translate protocol and clip by {@link Maturity}; it composes registered operations,
 * it never invents new ones. {@code REST} is reserved for a future GA face and carries no open exposure yet.
 */
public enum Frontend {
    CLI,
    MCP,
    REST
}
