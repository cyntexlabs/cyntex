/**
 * HTTP presentation adapter over the control core.
 *
 * <p>The Spring Boot Web MVC face: it projects the control verbs onto a single-context, single-port
 * HTTP surface served under the {@code /api} prefix. Rule R5: this module depends on control-core +
 * core only (not on the ports directly); Spring is admitted here, never in control-core.
 */
package io.cyntex.control.restapi;
