package io.cyntex.adapters.mongostore;

import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import io.cyntex.core.common.CyntexException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The single translation point from Mongo driver exceptions to io-domain coded diagnostics: a store
 * runs its driver call through here, so a driver failure surfaces as a coded {@code io.*} diagnostic
 * and no driver type escapes the module (rule R3). A security failure maps to
 * {@code io.store-unauthorized}; any other driver failure maps to {@code io.store-unavailable}
 * carrying the driver's detail. A non-driver throwable — a coded reconstruction failure
 * ({@code io.document-unreadable}) or a bare invariant crash — passes straight through, never
 * relabelled as a driver failure.
 */
final class StoreIo {

    private StoreIo() {
    }

    /** Runs a store operation, translating a driver failure into a coded io diagnostic. */
    static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (MongoException e) {
            throw coded(e);
        }
    }

    /** Runs a store operation with no result, translating a driver failure into a coded io diagnostic. */
    static void run(Runnable operation) {
        call(() -> {
            operation.run();
            return null;
        });
    }

    /** Translates a driver failure into its coded io diagnostic (without throwing it). */
    static CyntexException coded(MongoException e) {
        if (e instanceof MongoSecurityException) {
            return new CyntexException(IoError.STORE_UNAUTHORIZED, Map.of(), e);
        }
        return new CyntexException(IoError.STORE_UNAVAILABLE, Map.of("detail", detail(e)), e);
    }

    /** The driver's failure detail — its message, or its type when it carries none (never a credential). */
    private static String detail(MongoException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
