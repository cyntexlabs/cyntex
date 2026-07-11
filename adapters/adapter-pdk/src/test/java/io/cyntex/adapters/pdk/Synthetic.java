package io.cyntex.adapters.pdk;

import java.nio.file.Path;
import java.util.Map;

/**
 * Compiles full synthetic {@code TapConnector}s at test time (via {@link SyntheticJar}), so the PDK
 * bridge's drive and coded-error paths can be proven without any real connector jar or the PDK
 * runtime. Each connector implements the frozen contract directly and registers exactly the read /
 * write behaviour a test needs; the lifecycle and discovery methods are inert unless the test drives
 * them.
 */
final class Synthetic {

    private Synthetic() {
    }

    /** The shared source scaffold: a connector whose ctor and registered functions the caller fills in. */
    private static String source(String simpleName, String ctorBody, String registerBody) {
        return ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import io.tapdata.entity.schema.TapField;"
                + "import io.tapdata.entity.event.TapEvent;"
                + "import io.tapdata.entity.event.dml.TapInsertRecordEvent;"
                + "import io.tapdata.entity.event.dml.TapUpdateRecordEvent;"
                + "import io.tapdata.entity.event.dml.TapDeleteRecordEvent;"
                + "import java.util.ArrayList;"
                + "import java.util.LinkedHashMap;"
                + "import java.util.List;"
                + "import java.util.Map;"
                + "import java.util.function.Consumer;"
                + "public class " + simpleName + " implements TapConnector {"
                + "  public " + simpleName + "() {" + ctorBody + "}"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {"
                + registerBody
                + "  }"
                + "  public void init(TapConnectionContext c) {}"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {"
                + "    TapTable table = new TapTable(\"t1\");"
                + "    table.add(new TapField(\"id\", \"int\"));"
                + "    List<TapTable> tables = new ArrayList<>();"
                + "    tables.add(table);"
                + "    s.accept(tables);"
                + "  }"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) {"
                + "    s.accept(new TestItem(\"ping\", TestItem.RESULT_SUCCESSFULLY));"
                + "    return ConnectionOptions.create();"
                + "  }"
                + "  public int tableCount(TapConnectionContext c) { return 1; }"
                + "}";
    }

    /** A row map {@code {id: value}} as a Java expression string. */
    private static String row(String var, int id) {
        return "Map<String,Object> " + var + " = new LinkedHashMap<>(); " + var + ".put(\"id\", " + id + ");";
    }

    /** Batch-reads two snapshot rows and streams an insert / update / delete; a full source connector. */
    static Path emittingSource(Path dir) {
        String register = ""
                + "functions.supportBatchRead((context, table, offset, size, consumer) -> {"
                + "  List<TapEvent> evs = new ArrayList<>();"
                + row("r1", 1)
                + row("r2", 2)
                + "  evs.add(TapInsertRecordEvent.create().table(\"t1\").referenceTime(100L).after(r1));"
                + "  evs.add(TapInsertRecordEvent.create().table(\"t1\").referenceTime(100L).after(r2));"
                + "  consumer.accept(evs, null);"
                + "});"
                + "functions.supportStreamRead((context, tables, offset, size, consumer) -> {"
                + "  consumer.streamReadStarted();"
                + row("a", 1)
                + "  List<TapEvent> ins = new ArrayList<>();"
                + "  ins.add(TapInsertRecordEvent.create().table(\"t1\").referenceTime(1L).after(a));"
                + "  consumer.accept(ins, null);"
                + row("before", 1)
                + "  Map<String,Object> after = new LinkedHashMap<>(); after.put(\"id\", 1); after.put(\"v\", 2);"
                + "  List<TapEvent> upd = new ArrayList<>();"
                + "  upd.add(TapUpdateRecordEvent.create().table(\"t1\").referenceTime(2L).before(before).after(after));"
                + "  consumer.accept(upd, null);"
                + "  List<TapEvent> del = new ArrayList<>();"
                + "  del.add(TapDeleteRecordEvent.create().table(\"t1\").referenceTime(3L).before(before));"
                + "  consumer.accept(del, null);"
                + "  consumer.streamReadEnded();"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.EmittingSource", source("EmittingSource", "", register));
    }

    /** A connector whose constructor throws — instantiation fails, a load failure. */
    static Path ctorThrowsSource(Path dir) {
        return SyntheticJar.compileToJar(dir, "synthetic.CtorThrows",
                source("CtorThrows", "throw new RuntimeException(\"ctor boom\");", ""));
    }

    /** A connector whose batchRead throws — a connector-side read failure. */
    static Path throwingReadSource(Path dir) {
        String register = "functions.supportBatchRead((context, table, offset, size, consumer) -> {"
                + "  throw new RuntimeException(\"read boom\");"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.ThrowingRead", source("ThrowingRead", "", register));
    }

    /** A connector whose batchRead emits a delete-shaped event — unprojectable as a snapshot row. */
    static Path badRowSource(Path dir) {
        String register = "functions.supportBatchRead((context, table, offset, size, consumer) -> {"
                + "  List<TapEvent> evs = new ArrayList<>();"
                + row("b", 1)
                + "  evs.add(TapDeleteRecordEvent.create().table(\"t1\").referenceTime(1L).before(b));"
                + "  consumer.accept(evs, null);"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.BadRow", source("BadRow", "", register));
    }

    /** A sink connector whose writeRecord counts events and reports them all inserted. */
    static Path countingSink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>((long) events.size(), 0L, 0L));"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.CountingSink", source("CountingSink", "", register));
    }

    /** A sink connector whose writeRecord rejects any event that is not an insert — proves append reforge. */
    static Path insertsOnlySink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  for (Object e : events) {"
                + "    if (!(e instanceof io.tapdata.entity.event.dml.TapInsertRecordEvent)) {"
                + "      throw new RuntimeException(\"non-insert event: \" + e.getClass().getName());"
                + "    }"
                + "  }"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>((long) events.size(), 0L, 0L));"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.InsertsOnly", source("InsertsOnly", "", register));
    }

    /** A sink connector whose writeRecord throws — a connector-side write failure. */
    static Path throwingWriteSink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  throw new RuntimeException(\"write boom\");"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.ThrowingWrite", source("ThrowingWrite", "", register));
    }

    /** A sink connector whose writeRecord reports each batch in two flushes — proves count accumulation. */
    static Path multiFlushSink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>(1L, 0L, 0L));"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>((long) (events.size() - 1), 0L, 0L));"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.MultiFlush", source("MultiFlush", "", register));
    }

    /** A source that refuses a second init on the same instance — proves the drive inits exactly once. */
    static Path singleInitSource(Path dir) {
        String src = ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import io.tapdata.entity.schema.TapField;"
                + "import io.tapdata.entity.event.TapEvent;"
                + "import io.tapdata.entity.event.dml.TapInsertRecordEvent;"
                + "import java.util.ArrayList;"
                + "import java.util.LinkedHashMap;"
                + "import java.util.List;"
                + "import java.util.Map;"
                + "import java.util.function.Consumer;"
                + "public class SingleInit implements TapConnector {"
                + "  private int inits = 0;"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {"
                + "    functions.supportBatchRead((context, table, offset, size, consumer) -> {"
                + "      List<TapEvent> evs = new ArrayList<>();"
                + "      Map<String,Object> r = new LinkedHashMap<>(); r.put(\"id\", 1);"
                + "      evs.add(TapInsertRecordEvent.create().table(\"t1\").referenceTime(1L).after(r));"
                + "      consumer.accept(evs, null);"
                + "    });"
                + "  }"
                + "  public void init(TapConnectionContext c) { if (++inits > 1) throw new RuntimeException(\"init called twice\"); }"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {"
                + "    TapTable table = new TapTable(\"t1\"); table.add(new TapField(\"id\", \"int\"));"
                + "    List<TapTable> tables = new ArrayList<>(); tables.add(table); s.accept(tables);"
                + "  }"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { return ConnectionOptions.create(); }"
                + "  public int tableCount(TapConnectionContext c) { return 1; }"
                + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.SingleInit", src);
    }

    /** A connector whose static initializer throws — construction fails with a link/init Error, not a RuntimeException. */
    static Path staticThrowsSource(Path dir) {
        String src = ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import java.util.List;"
                + "import java.util.function.Consumer;"
                + "public class StaticThrows implements TapConnector {"
                + "  static { if (Boolean.parseBoolean(\"true\")) throw new RuntimeException(\"static boom\"); }"
                + "  public void registerCapabilities(ConnectorFunctions f, TapCodecsRegistry c) {}"
                + "  public void init(TapConnectionContext c) {}"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {}"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { return ConnectionOptions.create(); }"
                + "  public int tableCount(TapConnectionContext c) { return 0; }"
                + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.StaticThrows", src);
    }

    /**
     * A source that registers batch read but throws from every lifecycle method (init / stop /
     * discover / connectionTest / tableCount). A capability derive reads only the registered ids, so a
     * successful derive proves it inited nothing and opened no connection.
     */
    static Path initHostileSource(Path dir) {
        String src = ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import java.util.List;"
                + "import java.util.function.Consumer;"
                + "public class InitHostile implements TapConnector {"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {"
                + "    functions.supportBatchRead((a, b, c, d, e) -> {});"
                + "  }"
                + "  public void init(TapConnectionContext c) { throw new AssertionError(\"init called during capability derive\"); }"
                + "  public void stop(TapConnectionContext c) { throw new AssertionError(\"stop called during capability derive\"); }"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) { throw new AssertionError(\"discoverSchema called during capability derive\"); }"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { throw new AssertionError(\"connectionTest called during capability derive\"); }"
                + "  public int tableCount(TapConnectionContext c) { throw new AssertionError(\"tableCount called during capability derive\"); }"
                + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.InitHostile", src);
    }

    // ---- connection-test connectors (drive TapConnectorNode.connectionTest) -----------------------

    /**
     * A minimal connector whose connectionTest body the caller fills in — no read/write functions, inert
     * lifecycle and discovery. The body streams TestItems to the consumer and returns ConnectionOptions,
     * or throws to exercise the coded drive-failure path. connectionTest creates and releases its own
     * connection, so the drive never inits or stops the node around it.
     */
    private static String connectionTestSource(String simpleName, String testBody) {
        return ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.pdk.apis.exception.TapTestItemException;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import java.util.List;"
                + "import java.util.function.Consumer;"
                + "public class " + simpleName + " implements TapConnector {"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {}"
                + "  public void init(TapConnectionContext c) {}"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {}"
                + "  public int tableCount(TapConnectionContext c) { return 0; }"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) {" + testBody + "}"
                + "}";
    }

    /** A connector whose connectionTest reports a single passing check. */
    static Path passingTest(Path dir) {
        String body = ""
                + "s.accept(new TestItem(\"Connection\", TestItem.RESULT_SUCCESSFULLY));"
                + "return ConnectionOptions.create();";
        return SyntheticJar.compileToJar(dir, "synthetic.PassingTest", connectionTestSource("PassingTest", body));
    }

    /** A connector whose connectionTest passes but attaches a warning item carrying free-text information. */
    static Path warningTest(Path dir) {
        String body = ""
                + "s.accept(new TestItem(\"Connection\", TestItem.RESULT_SUCCESSFULLY));"
                + "s.accept(new TestItem(\"Time detection\", TestItem.RESULT_SUCCESSFULLY_WITH_WARN, \"clock skew 3s\"));"
                + "return ConnectionOptions.create();";
        return SyntheticJar.compileToJar(dir, "synthetic.WarningTest", connectionTestSource("WarningTest", body));
    }

    /** A connector whose connectionTest reports a failed check with a full structured diagnostic. */
    static Path failingTest(Path dir) {
        String body = ""
                + "s.accept(new TestItem(\"Connection\", TestItem.RESULT_SUCCESSFULLY));"
                + "TapTestItemException ex = new TapTestItemException();"
                + "ex.setMessage(\"Login denied\");"
                + "ex.setReason(\"bad credentials\");"
                + "ex.setSolution(\"check username/password\");"
                + "ex.setErrorCode(\"28000\");"
                + "s.accept(new TestItem(\"Login\", ex, TestItem.RESULT_FAILED));"
                + "return ConnectionOptions.create();";
        return SyntheticJar.compileToJar(dir, "synthetic.FailingTest", connectionTestSource("FailingTest", body));
    }

    /** A connector whose connectionTest throws — the test could not be completed, a coded drive failure. */
    static Path throwingTest(Path dir) {
        String body = "throw new RuntimeException(\"test boom\");";
        return SyntheticJar.compileToJar(dir, "synthetic.ThrowingTest", connectionTestSource("ThrowingTest", body));
    }

    // ---- discover-schema connectors (drive TapConnector.discoverSchema) ---------------------------

    /**
     * A minimal connector whose discoverSchema body the caller fills in — no read/write functions, inert
     * lifecycle and connection test. The body streams TapTables to the consumer, or throws to exercise
     * the coded discover-failure path.
     */
    private static String discoverSource(String simpleName, String discoverBody) {
        return ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import io.tapdata.entity.schema.TapField;"
                + "import io.tapdata.entity.schema.TapIndex;"
                + "import io.tapdata.entity.schema.TapIndexField;"
                + "import java.util.ArrayList;"
                + "import java.util.List;"
                + "import java.util.function.Consumer;"
                + "public class " + simpleName + " implements TapConnector {"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {}"
                + "  public void init(TapConnectionContext c) {}"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { return ConnectionOptions.create(); }"
                + "  public int tableCount(TapConnectionContext c) { return 1; }"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {" + discoverBody + "}"
                + "}";
    }

    /**
     * A connector whose discoverSchema reports one table with a primary-key field, a plain field and a
     * non-unique secondary index — enough to prove field, primary-key and index normalization.
     */
    static Path discoverableSource(Path dir) {
        String body = ""
                + "TapTable table = new TapTable(\"orders\");"
                + "table.add(new TapField(\"id\", \"int\").isPrimaryKey(true).primaryKeyPos(1));"
                + "table.add(new TapField(\"amount\", \"decimal\"));"
                + "table.add(new TapIndex().name(\"idx_amount\").unique(false)"
                + "    .indexField(new TapIndexField().name(\"amount\").fieldAsc(true)));"
                + "List<TapTable> tables = new ArrayList<>();"
                + "tables.add(table);"
                + "s.accept(tables);";
        return SyntheticJar.compileToJar(dir, "synthetic.Discoverable", discoverSource("Discoverable", body));
    }

    /** A connector whose discoverSchema throws — discovery could not complete, a coded drive failure. */
    static Path throwingDiscoverSource(Path dir) {
        String body = "throw new RuntimeException(\"discover boom\");";
        return SyntheticJar.compileToJar(dir, "synthetic.ThrowingDiscover", discoverSource("ThrowingDiscover", body));
    }

    // ---- self-scan connectors (drive ConnectorIntrospector) ---------------------------------------

    /** The imports every self-scan fixture's source needs. */
    private static final String SELF_SCAN_IMPORTS = ""
            + "package synthetic;"
            + "import io.tapdata.pdk.apis.TapConnector;"
            + "import io.tapdata.pdk.apis.annotations.TapConnectorClass;"
            + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
            + "import io.tapdata.entity.codec.TapCodecsRegistry;"
            + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
            + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
            + "import io.tapdata.pdk.apis.entity.TestItem;"
            + "import io.tapdata.entity.schema.TapTable;"
            + "import java.util.List;"
            + "import java.util.function.Consumer;";

    /** The frozen TapConnector methods, all inert: introspection reads a class's annotation, spec and
     *  manifest and never drives it, so a successful introspect proves none of these ran. */
    private static final String INERT_CONNECTOR_BODY = ""
            + "  public void registerCapabilities(ConnectorFunctions f, TapCodecsRegistry c) {}"
            + "  public void init(TapConnectionContext c) {}"
            + "  public void stop(TapConnectionContext c) {}"
            + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {}"
            + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { return ConnectionOptions.create(); }"
            + "  public int tableCount(TapConnectionContext c) { return 0; }";

    /**
     * A single-connector jar: one {@code @TapConnectorClass} entry class, the {@code orders-spec.json}
     * resource its annotation names, and a {@code PDK-API-Version} manifest attribute — the exact shape
     * a real connector dist jar has, and what self-scan introspects.
     */
    static Path annotatedConnector(Path dir) {
        String src = SELF_SCAN_IMPORTS
                + "@TapConnectorClass(\"orders-spec.json\")"
                + "public class OrdersConnector implements TapConnector {" + INERT_CONNECTOR_BODY + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.OrdersConnector", src,
                Map.of("orders-spec.json", "{\"id\":\"orders\"}"),
                Map.of("PDK-API-Version", "1.3.5"));
    }

    /** A jar with a plain class and no {@code @TapConnectorClass} anywhere — not a connector artifact. */
    static Path jarWithoutConnectorClass(Path dir) {
        return SyntheticJar.compileToJar(dir, "synthetic.NotAConnector",
                "package synthetic; public class NotAConnector {}");
    }

    /** An annotated connector whose {@code @TapConnectorClass} names a spec the jar does not contain. */
    static Path annotatedConnectorMissingSpec(Path dir) {
        String src = SELF_SCAN_IMPORTS
                + "@TapConnectorClass(\"absent-spec.json\")"
                + "public class GhostConnector implements TapConnector {" + INERT_CONNECTOR_BODY + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.GhostConnector", src,
                Map.of(), // the annotation names a spec, but none is packaged
                Map.of("PDK-API-Version", "1.3.5"));
    }

    /** One jar carrying two unrelated annotated connectors — an ambiguous artifact for registration. */
    static Path twoDistinctConnectors(Path dir) {
        String src = SELF_SCAN_IMPORTS
                + "@TapConnectorClass(\"a-spec.json\")"
                + "public class ConnectorA implements TapConnector {" + INERT_CONNECTOR_BODY + "}"
                + "@TapConnectorClass(\"b-spec.json\")"
                + "class ConnectorB implements TapConnector {" + INERT_CONNECTOR_BODY + "}";
        return SyntheticJar.compileToJar(dir, "synthetic.ConnectorA", src,
                Map.of("a-spec.json", "{\"id\":\"a\"}", "b-spec.json", "{\"id\":\"b\"}"),
                Map.of("PDK-API-Version", "1.3.5"));
    }

    /** An annotated connector that registers a read capability — proves an introspected artifact drives
     *  capability derivation, closing the self-scan to capability-derive seam. */
    static Path annotatedEmittingConnector(Path dir) {
        String body = ""
                + "  public void registerCapabilities(ConnectorFunctions f, TapCodecsRegistry c) {"
                + "    f.supportBatchRead((context, table, offset, size, consumer) -> {});"
                + "  }"
                + "  public void init(TapConnectionContext c) {}"
                + "  public void stop(TapConnectionContext c) {}"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) {}"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { return ConnectionOptions.create(); }"
                + "  public int tableCount(TapConnectionContext c) { return 0; }";
        String src = SELF_SCAN_IMPORTS
                + "@TapConnectorClass(\"reader-spec.json\")"
                + "public class ReaderConnector implements TapConnector {" + body + "}";
        // A registered PDK API version, so the introspected version passes the deriver's level gate.
        return SyntheticJar.compileToJar(dir, "synthetic.ReaderConnector", src,
                Map.of("reader-spec.json", "{\"id\":\"reader\"}"),
                Map.of("PDK-API-Version", "2.0.8"));
    }

    /** An annotated connector that subclasses an also-annotated base — the variant is the real entry. */
    static Path baseAndVariantConnector(Path dir) {
        String src = SELF_SCAN_IMPORTS
                + "@TapConnectorClass(\"base-spec.json\")"
                + "class BaseConnector implements TapConnector {" + INERT_CONNECTOR_BODY + "}"
                + "@TapConnectorClass(\"variant-spec.json\")"
                + "public class VariantConnector extends BaseConnector {}";
        return SyntheticJar.compileToJar(dir, "synthetic.VariantConnector", src,
                Map.of("base-spec.json", "{\"id\":\"base\"}", "variant-spec.json", "{\"id\":\"variant\"}"),
                Map.of("PDK-API-Version", "1.3.5"));
    }
}
