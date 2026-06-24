package io.cyntex.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.cyntex.core.model.SourceMode;

class GroupRulesTest {

    @Test
    void databaseTagsClassifyAsDatabase() {
        assertThat(GroupRules.fromTags(List.of("Database", "ssl", "doubleActive")))
                .isEqualTo(ConnectorGroup.DATABASE);
    }

    @Test
    void fileTagsClassifyAsFile() {
        assertThat(GroupRules.fromTags(List.of("File", "schema-free"))).isEqualTo(ConnectorGroup.FILE);
    }

    @Test
    void saasTagsClassifyAsSaasAcceptingTheTypoVariant() {
        assertThat(GroupRules.fromTags(List.of("SaaS"))).isEqualTo(ConnectorGroup.SAAS);
        assertThat(GroupRules.fromTags(List.of("Saas"))).isEqualTo(ConnectorGroup.SAAS);
    }

    @Test
    void kafkaTagsItselfDatabaseSoTagsAloneAreHonestlyWrong() {
        // Kafka's tags are ["Database","schema-free"]; fromTags reports what the tags say.
        assertThat(GroupRules.fromTags(List.of("Database", "schema-free")))
                .isEqualTo(ConnectorGroup.DATABASE);
    }

    @Test
    void unknownOrEmptyTagsAreOther() {
        assertThat(GroupRules.fromTags(List.of())).isEqualTo(ConnectorGroup.OTHER);
        assertThat(GroupRules.fromTags(List.of("schema-free"))).isEqualTo(ConnectorGroup.OTHER);
        assertThat(GroupRules.fromTags(null)).isEqualTo(ConnectorGroup.OTHER);
    }

    @Test
    void knownMessageQueueNamesAreDetected() {
        assertThat(GroupRules.isLikelyMessageQueue("kafka")).isTrue();
        assertThat(GroupRules.isLikelyMessageQueue("rocketmq")).isTrue();
        assertThat(GroupRules.isLikelyMessageQueue("rabbitmq")).isTrue();
        assertThat(GroupRules.isLikelyMessageQueue("pulsar")).isTrue();
        assertThat(GroupRules.isLikelyMessageQueue("mysql")).isFalse();
    }

    @Test
    void refineCorrectsKafkaToMqViaStreamModeAndName() {
        // Kafka: tag guess DATABASE, but its only resolved mode is stream → MQ.
        ConnectorGroup group = GroupRules.refine(
                ConnectorGroup.DATABASE, Set.of(SourceMode.STREAM), "kafka");
        assertThat(group).isEqualTo(ConnectorGroup.MQ);
    }

    @Test
    void refineKeepsDatabaseForACdcSnapshotConnector() {
        ConnectorGroup group = GroupRules.refine(
                ConnectorGroup.DATABASE, Set.of(SourceMode.CDC, SourceMode.SNAPSHOT), "mysql");
        assertThat(group).isEqualTo(ConnectorGroup.DATABASE);
    }

    @Test
    void refinePromotesAnApiOnlyConnectorToSaas() {
        ConnectorGroup group = GroupRules.refine(
                ConnectorGroup.OTHER, Set.of(SourceMode.API), "github");
        assertThat(group).isEqualTo(ConnectorGroup.SAAS);
    }

    @Test
    void refineKeepsFileForAFileConnector() {
        ConnectorGroup group = GroupRules.refine(
                ConnectorGroup.FILE, Set.of(SourceMode.FILE), "csv");
        assertThat(group).isEqualTo(ConnectorGroup.FILE);
    }

    @Test
    void refineRecoversDatabaseWhenTagsWereUselessButModesAreDbLike() {
        ConnectorGroup group = GroupRules.refine(
                ConnectorGroup.OTHER, Set.of(SourceMode.SNAPSHOT), "someDb");
        assertThat(group).isEqualTo(ConnectorGroup.DATABASE);
    }
}
