package io.cyntex.adapters.transform;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import io.cyntex.core.model.FieldRule;
import io.cyntex.core.model.TransformBody;
import io.cyntex.spi.transform.TransformPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The projection spec is what the app captures in the Jet supplier, so it must survive serialization
 * and still rebuild a working port on the far side — the port itself is built member-side, from this
 * spec, after it has crossed. A round trip that then projects correctly proves both.
 */
class MapSpecSerializationTest {

    @Test
    @DisplayName("a map spec survives java serialization and still projects on the far side")
    void mapSpecRoundTripsAndStillProjects() throws IOException, ClassNotFoundException {
        LinkedHashMap<String, FieldRule> fields = new LinkedHashMap<>();
        fields.put("full_name", FieldRule.rename("name"));
        fields.put("secret", FieldRule.drop());
        fields.put("stage", FieldRule.literal("prod"));
        fields.put("greeting", FieldRule.computed("'hi ' + after.name"));
        MapSpec spec = MapSpec.from(new TransformBody.MapProjection(fields));

        MapSpec restored = roundTrip(spec);

        TransformPort map = StatelessTransforms.map(restored);
        Envelope row = Envelope.insert(1L, "t",
                new LinkedHashMap<>(Map.of("name", "ada", "secret", "x", "age", 30)), null);
        Map<String, Object> after = map.transform(row).get(0).after();
        assertThat(after)
                .containsEntry("full_name", "ada")
                .containsEntry("stage", "prod")
                .containsEntry("greeting", "hi ada")
                .containsEntry("age", 30)
                .doesNotContainKey("secret")
                .doesNotContainKey("name");
    }

    private static MapSpec roundTrip(MapSpec spec) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(spec);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (MapSpec) in.readObject();
        }
    }
}
