package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.spi.sink.DdlPolicy;
import io.cyntex.spi.sink.TargetField;
import io.cyntex.spi.sink.TargetTable;
import io.cyntex.spi.sink.WriteMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sink factory is shipped onto the Jet DAG, so it and everything it carries must serialize. This guards
 * that a factory holding a resolved target model round-trips, which the engine relies on to run the sink on
 * the member that opens the connector.
 */
class PdkSinkWriterFactorySerializationTest {

    @Test
    void serializes_with_its_resolved_target_so_it_can_ship_onto_the_dag() throws Exception {
        PdkSinkWriterFactory factory = new PdkSinkWriterFactory(
                "mongodb", Map.of("uri", "u"), WriteMode.UPSERT, DdlPolicy.APPLY,
                new TargetTable("orders", List.of(
                        new TargetField("id", "INT", true),
                        new TargetField("amount", "DECIMAL", false))));

        Object restored = roundTrip(factory);

        assertThat(restored).isInstanceOf(PdkSinkWriterFactory.class);
    }

    private static Object roundTrip(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }
}
