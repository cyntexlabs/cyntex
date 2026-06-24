package io.cyntex.core.dsl;

import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mode × option compatibility (ADR-0016 §4 / X7 / X10) — the offline semantic half of the
 * capability matrix that needs no connector catalog (intra-DSL mode rules only; connector × mode
 * legality is plan task C3). Runs after reference closure as part of {@link Workspace#of}; the
 * first violation throws {@link DslError#MODE_MISMATCH}.
 *
 * <ul>
 *   <li>an {@code srs} block is legal only for {@code mode: cdc} — SRS derives solely from
 *       unbounded + keyed change semantics;</li>
 *   <li>{@code options.start_from} is legal only for {@code mode: stream}, or {@code mode: cdc}
 *       with {@code snapshot_mode: never} (X10);</li>
 *   <li>{@code settings.schedule} is legal only when every referenced source mode is bounded — a
 *       continuous (cdc / stream) source has no re-run concept (X7).</li>
 * </ul>
 */
final class ModeRules {

    private ModeRules() {
    }

    static void validate(Collection<Resource> batch) {
        Map<String, SourceResource> sources = new LinkedHashMap<>();
        for (Resource r : batch) {
            if (r instanceof SourceResource s) {
                sources.put(s.id(), s);
            }
        }
        for (Resource r : batch) {
            if (r instanceof SourceResource s) {
                checkSource(s);
            } else if (r instanceof PipelineResource p) {
                checkSchedule(p, sources);
            }
        }
    }

    private static void checkSource(SourceResource s) {
        if (s.srs() != null && s.mode() != SourceMode.CDC) {
            throw modeMismatch("srs", "srs", s.mode());
        }
        if (hasOption(s, "start_from") && !allowsStartFrom(s)) {
            throw modeMismatch("start_from", "options.start_from", s.mode());
        }
    }

    private static boolean allowsStartFrom(SourceResource s) {
        return s.mode() == SourceMode.STREAM
                || (s.mode() == SourceMode.CDC && "never".equals(option(s, "snapshot_mode")));
    }

    private static void checkSchedule(PipelineResource p, Map<String, SourceResource> sources) {
        if (p.settings() == null || p.settings().schedule() == null) {
            return;
        }
        for (String sid : p.sources()) {
            SourceResource s = sources.get(sid);    // existence already proved by reference closure
            if (s != null && isUnbounded(s.mode())) {
                throw modeMismatch("schedule", "settings.schedule", s.mode());
            }
        }
    }

    private static boolean isUnbounded(SourceMode mode) {
        return mode == SourceMode.CDC || mode == SourceMode.STREAM;
    }

    private static boolean hasOption(SourceResource s, String key) {
        return s.options() != null && s.options().containsKey(key);
    }

    private static String option(SourceResource s, String key) {
        Object v = s.options() == null ? null : s.options().get(key);
        return v == null ? null : v.toString();
    }

    private static DslException modeMismatch(String field, String path, SourceMode mode) {
        return new DslException(DslError.MODE_MISMATCH, path, 0, 0, null,
                Map.of("field", field, "mode", mode == null ? "(none)" : mode.yaml()));
    }
}
