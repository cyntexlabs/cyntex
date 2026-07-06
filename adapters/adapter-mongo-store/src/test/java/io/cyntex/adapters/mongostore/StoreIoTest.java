package io.cyntex.adapters.mongostore;

import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * StoreIo is the single translation point from driver exceptions to io-domain coded diagnostics, so
 * no driver type escapes the module (rule R3). A driver security failure maps to store-unauthorized,
 * any other driver failure to store-unavailable carrying the driver's detail, and a non-driver
 * throwable passes straight through — a coded reconstruction failure or a bare invariant crash must
 * not be relabelled a driver failure.
 */
class StoreIoTest {

    @Test
    void returnsTheOperationResultOnSuccess() {
        assertThat(StoreIo.call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void mapsADriverFailureToStoreUnavailableCarryingTheDetail() {
        Throwable thrown = catchThrowable(() -> StoreIo.call(() -> {
            throw new MongoException("connection reset");
        }));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.STORE_UNAVAILABLE);
        assertThat(coded.args()).containsEntry("detail", "connection reset");
    }

    @Test
    void mapsADriverSecurityFailureToStoreUnauthorized() {
        MongoSecurityException auth = new MongoSecurityException(
                MongoCredential.createCredential("u", "admin", "p".toCharArray()), "auth failed");

        Throwable thrown = catchThrowable(() -> StoreIo.call(() -> {
            throw auth;
        }));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.STORE_UNAUTHORIZED);
        assertThat(coded.args()).isEmpty();
    }

    @Test
    void mapsADriverFailureWithNoMessageToItsType() {
        Throwable thrown = catchThrowable(() -> StoreIo.call(() -> {
            throw new MongoException((String) null);
        }));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).args()).containsEntry("detail", "MongoException");
    }

    @Test
    void passesANonDriverThrowableThrough() {
        assertThatThrownBy(() -> StoreIo.call(() -> {
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class).hasMessage("bug");
    }
}
