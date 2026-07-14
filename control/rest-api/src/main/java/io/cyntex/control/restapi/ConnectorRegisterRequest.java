package io.cyntex.control.restapi;

/**
 * The body of a register request: the connector artifact to register, as a base64-encoded jar. The
 * artifact travels as base64 in the JSON body because the CLI speaks HTTP only and shares no filesystem
 * with the server, so it hands over the bytes rather than a path; the server decodes them, introspects
 * the connector, and stores it in the distribution store.
 */
record ConnectorRegisterRequest(String artifact) {
}
