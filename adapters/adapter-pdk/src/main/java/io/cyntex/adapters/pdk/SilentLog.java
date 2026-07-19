package io.cyntex.adapters.pdk;

import io.tapdata.entity.logger.Log;

/**
 * A no-op connector log for the driving context. A connector — synthetic or real — writes its own log
 * output through this; routing that output to the host's operational logging is a later runtime concern,
 * so for now it is dropped rather than surfaced. The context carries this silent log rather than a null
 * the connector could dereference.
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
