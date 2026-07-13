package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineLogs;
import io.cyntex.control.core.PipelineStatus;
import io.cyntex.core.common.JsonWriter;
import io.cyntex.core.logging.LogLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a streamed status or logs frame to the same compact JSON the one-shot {@code GET} read faces
 * return, so the CLI decodes a frame from the websocket with the exact decoder it uses for a polled
 * read. The rest-api ring carries no JSON library (only Spring's message converters serialise the REST
 * bodies, and those are not reachable as a plain object-to-string call here), so the tree is built by
 * hand and rendered through the core {@link JsonWriter}, which does the escaping.
 */
final class StreamFrames {

    private StreamFrames() {
    }

    /** A status frame: {@code {"pipelineId":..,"state":..}}, the state as its wire name. */
    static String status(PipelineStatus status) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("pipelineId", status.pipelineId());
        frame.put("state", status.state().name());
        return JsonWriter.write(frame);
    }

    /** A logs frame: {@code {"pipelineId":..,"lines":[{timestampMillis,level,message},..]}}. */
    static String logs(PipelineLogs logs) {
        List<Object> lines = new ArrayList<>();
        for (LogLine line : logs.lines()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("timestampMillis", line.timestampMillis());
            encoded.put("level", line.level());
            encoded.put("message", line.message());
            lines.add(encoded);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("pipelineId", logs.pipelineId());
        frame.put("lines", lines);
        return JsonWriter.write(frame);
    }
}
