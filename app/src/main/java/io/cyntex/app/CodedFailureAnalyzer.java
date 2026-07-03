package io.cyntex.app;

import io.cyntex.core.common.CyntexException;
import io.cyntex.messages.MessageCatalog;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a coded {@link CyntexException} that aborts startup (e.g. the store is unreachable) into a
 * clean, operator-facing diagnostic rendered through the shared message catalog — the same rendering
 * the CLI uses — instead of a raw stack trace. Programmer errors are not coded, so they keep their
 * stack trace and are unaffected.
 */
public class CodedFailureAnalyzer extends AbstractFailureAnalyzer<CyntexException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CyntexException cause) {
        MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(cause.code(), cause.args());
        return new FailureAnalysis(rendered.message(), rendered.solution(), cause);
    }
}
