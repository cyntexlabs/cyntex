package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.cyntex.spi.store.User;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the user store against a real Mongo replica-set: a saved user reads back with the same
 * fields (through the real bson encode), an absent username reads back empty, and a re-save of the
 * same username replaces in place (keyed by _id) rather than accumulating a second document. Skipped
 * automatically where Docker is absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoUserStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    @Test
    void savedUserReadsBackWithItsFields() {
        withStore((store, collection) -> {
            store.save(new User("alice", "hash-abc", "admin"));

            Optional<User> read = store.find("alice");
            assertThat(read).contains(new User("alice", "hash-abc", "admin"));
        });
    }

    @Test
    void findReturnsEmptyForAnAbsentUsername() {
        withStore((store, collection) -> assertThat(store.find("nobody")).isEmpty());
    }

    @Test
    void reSaveOfTheSameUsernameReplacesInPlace() {
        withStore((store, collection) -> {
            store.save(new User("alice", "hash-one", "read"));
            store.save(new User("alice", "hash-two", "admin"));

            assertThat(collection.countDocuments()).isEqualTo(1);
            assertThat(store.find("alice")).contains(new User("alice", "hash-two", "admin"));
        });
    }

    private interface StoreTest {
        void run(MongoUserStore store, MongoCollection<Document> collection);
    }

    /** Runs a test body against a fresh user store over a clean collection on the real replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("cyntex").getCollection("users");
            collection.drop();
            test.run(new MongoUserStore(collection), collection);
        }
    }
}
