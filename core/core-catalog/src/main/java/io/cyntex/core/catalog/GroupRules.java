package io.cyntex.core.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.cyntex.core.model.SourceMode;

/**
 * Classifies a connector into a {@link ConnectorGroup}. Tags give a first guess but lie often
 * (Kafka tags itself {@code Database}); {@link #refine} corrects the guess using the connector's
 * resolved modes and a message-queue name heuristic.
 */
public final class GroupRules {

    /** Distinctive substrings of message-queue connector ids (Kafka mis-tags itself Database). */
    private static final List<String> MQ_NAME_TOKENS =
            List.of("kafka", "rabbit", "rocket", "pulsar", "activemq", "mqtt");

    private GroupRules() {
    }

    /**
     * First-guess group from the connector's {@code properties.tags}. File and SaaS take precedence
     * over Database because a connector rarely carries both honestly; the {@code Saas} typo is
     * accepted alongside {@code SaaS}.
     */
    public static ConnectorGroup fromTags(List<String> tags) {
        if (tags == null) {
            return ConnectorGroup.OTHER;
        }
        if (hasTag(tags, "File")) {
            return ConnectorGroup.FILE;
        }
        if (hasTag(tags, "SaaS") || hasTag(tags, "Saas")) {
            return ConnectorGroup.SAAS;
        }
        if (hasTag(tags, "MQ") || hasTag(tags, "MessageQueue")) {
            return ConnectorGroup.MQ;
        }
        if (hasTag(tags, "Database")) {
            return ConnectorGroup.DATABASE;
        }
        return ConnectorGroup.OTHER;
    }

    /** Whether the connector id looks like a message-queue connector (name heuristic). */
    public static boolean isLikelyMessageQueue(String connectorId) {
        if (connectorId == null) {
            return false;
        }
        String id = connectorId.toLowerCase(Locale.ROOT);
        for (String token : MQ_NAME_TOKENS) {
            if (id.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Final group: tag guess corrected against resolved modes and the MQ name heuristic. */
    public static ConnectorGroup refine(ConnectorGroup tagGroup, Set<SourceMode> modes, String connectorId) {
        if (modes.equals(Set.of(SourceMode.STREAM)) || isLikelyMessageQueue(connectorId)) {
            return ConnectorGroup.MQ;
        }
        if (modes.equals(Set.of(SourceMode.API))) {
            return ConnectorGroup.SAAS;
        }
        if (modes.contains(SourceMode.FILE)) {
            return ConnectorGroup.FILE;
        }
        if (tagGroup == ConnectorGroup.OTHER
                && (modes.contains(SourceMode.CDC) || modes.contains(SourceMode.SNAPSHOT))) {
            return ConnectorGroup.DATABASE;
        }
        return tagGroup;
    }

    private static boolean hasTag(List<String> tags, String tag) {
        for (String t : tags) {
            if (tag.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }
}
