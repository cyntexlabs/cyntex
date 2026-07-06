package io.cyntex.adapters.pdk;

/**
 * The PDK adapter module's assembly marker — the class the service assembly root names to wire this
 * adapter in. It carries no behavior itself; the bridge is the capture and sink ports
 * ({@link PdkCapturePort} / {@link PdkSinkPort}) backed by the connector ecosystem. Rule R3: this is
 * the only module permitted to import the PDK API.
 */
public final class PdkAdapter {

    private PdkAdapter() {
    }
}
