package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoStoreAdapter;
import io.cyntex.adapters.pdk.PdkAdapter;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.common.Severity;
import io.cyntex.messages.MessageCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * The service assembly root: the single entry point that brings the whole platform up in one JVM.
 * It resolves {@code --role} first and fails fast on an unsupported value — rendered as a coded
 * diagnostic — then starts the application context. At L1 the only supported role is {@code all};
 * conditional per-role wiring of the service rings lands later.
 *
 * <p>This is the only non-adapter module permitted to depend on the adapters ring (rule R7): the
 * assembly root is where the adapter bridges are bound into the runtime. At L1 the bridges are
 * placeholders, so {@link #adapterBridges()} records the wiring surface without engaging it.
 *
 * <p>The Mongo driver is on the classpath (through the store adapter), but the framework must not
 * stand up its own, unmanaged store client — the only store client is the controlled
 * {@link StoreConfiguration} connection. The base starter pulls no Mongo auto-configuration, so no
 * exclusion is needed; a test guards that no auto-configured Mongo client bean appears.
 */
@SpringBootApplication
public class Bootstrap {

    /** Exit code when a coded diagnostic was reported (matches the CLI's convention). */
    private static final int EXIT_CODED_DIAGNOSTIC = 1;

    public static void main(String[] args) {
        try {
            RoleArguments.parse(args);
        } catch (CyntexException e) {
            MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(e.code(), e.args());
            System.err.println(rendered.message());
            if (rendered.solution() != null) {
                System.err.println(rendered.solution());
            }
            System.exit(e.code().severity() == Severity.ERROR ? EXIT_CODED_DIAGNOSTIC : 0);
            return;
        }
        SpringApplication app = new SpringApplication(Bootstrap.class);
        // The server owns process termination. Spring's own shutdown hook would close the context on
        // SIGTERM but leave the JVM to exit with the signal's default disposition (128+15 = 143);
        // disabling it and installing our own hook lets the orderly stop finish and then force
        // exit 0, which is what an operator asking the process to stop expects.
        app.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = app.run(args);
        new GracefulShutdown(context::close, Runtime.getRuntime()::halt).install();
    }

    /**
     * The adapter bridges the assembly root binds into the runtime under {@code --role=all}. The app
     * is the only non-adapter module allowed to depend on the adapters ring (rule R7); the bridges
     * are placeholders at L1, so this records the wiring surface without engaging it.
     */
    static List<Class<?>> adapterBridges() {
        return List.of(PdkAdapter.class, MongoStoreAdapter.class);
    }
}
