package io.cyntex.adapters.pdk;

import java.nio.file.Path;

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

    /** A sink whose writeRecord reports the target table's primary-key count — proves the key reached it. */
    static Path keyCountingSink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  int pk = table.primaryKeys() == null ? 0 : table.primaryKeys().size();"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>((long) pk, 0L, 0L));"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.KeyCounting", source("KeyCounting", "", register));
    }

    /** A sink whose writeRecord reports the target table's column count — proves the schema reached it. */
    static Path fieldCountingSink(Path dir) {
        String register = "functions.supportWriteRecord((context, events, table, consumer) -> {"
                + "  int cols = table.getNameFieldMap() == null ? 0 : table.getNameFieldMap().size();"
                + "  consumer.accept(new io.tapdata.pdk.apis.entity.WriteListResult<>((long) cols, 0L, 0L));"
                + "});";
        return SyntheticJar.compileToJar(dir, "synthetic.FieldCounting", source("FieldCounting", "", register));
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
}
