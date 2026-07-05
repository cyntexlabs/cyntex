package io.cyntex.adapters.pdk;

import io.tapdata.entity.logger.Log;

/**
 * A no-op connector log for the driving context. Routing a connector's own log output to the host's
 * operational logging is a runtime concern (the runtime provides the real logger); until then the
 * bridge drives synthetic connectors that do not log, so the context carries a silent one rather than
 * a null the connector could dereference.
 */
final class SilentLog implements Log {

    @Override
    public void debug(String message, Object... params) {
    }

    @Override
    public void info(String message, Object... params) {
    }

    @Override
    public void trace(String message, Object... params) {
    }

    @Override
    public void warn(String message, Object... params) {
    }

    @Override
    public void error(String message, Object... params) {
    }

    @Override
    public void error(String message, Throwable throwable) {
    }

    @Override
    public void fatal(String message, Object... params) {
    }
}
