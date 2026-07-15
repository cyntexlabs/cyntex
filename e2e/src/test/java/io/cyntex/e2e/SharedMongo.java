package io.cyntex.e2e;

import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One replica set for every specification in the JVM.
 *
 * <p>A container per test class costs a start-up each time and buys nothing: runs stay independent
 * by taking a database of their own, not a daemon of their own. Ryuk reaps the container when the
 * JVM exits, so there is no stop to forget.
 */
final class SharedMongo {

    private static final DockerImageName IMAGE = DockerImageName.parse("mongo:7.0");

    private static MongoDBContainer container;

    private SharedMongo() {
    }

    /** The URL of a database on the shared replica set; the caller's name keeps its data its own. */
    static synchronized String replicaSetUrl(String database) {
        if (container == null) {
            DockerGate.require();
            MongoDBContainer starting = new MongoDBContainer(IMAGE);
            starting.start();
            container = starting;
        }
        return container.getReplicaSetUrl(database);
    }
}
