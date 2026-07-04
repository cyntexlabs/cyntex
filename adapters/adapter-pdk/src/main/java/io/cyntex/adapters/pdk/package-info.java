/**
 * PDK runtime bridge: the capture + sink adapter backed by the PDK connector ecosystem.
 *
 * <p>This is the only module allowed to import the PDK API (rule R3); it compiles against the frozen
 * PDK contract and keeps those types inside this package. Each connector loads through
 * {@link io.cyntex.adapters.pdk.ConnectorClassLoader} on its own isolated class loader over that one
 * shared contract, so connectors never see each other or the host application classes. The capability
 * harness, the API-level check, the TapEvent codec, and the spi capture/sink implementations are built
 * out on this base.
 */
package io.cyntex.adapters.pdk;
