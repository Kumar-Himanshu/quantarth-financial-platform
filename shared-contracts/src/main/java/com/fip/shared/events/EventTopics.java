package com.fip.shared.events;

public final class EventTopics {

    private EventTopics() {
        // Prevent instantiation
    }

    // Main Exchange and Dead-Letter Exchange
    public static final String EXCHANGE = "fip.financial.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "fip.dlx.exchange";

    // Routing Keys
    public static final String ROUTING_KEY_AUTH = "auth.events";
    public static final String ROUTING_KEY_PLUGIN = "plugin.events";
    public static final String ROUTING_KEY_EVENT = "financial.events.#";
    public static final String ROUTING_KEY_AUDIT = "financial.audit";
    public static final String ROUTING_KEY_AI = "ai.events";
    public static final String ROUTING_KEY_DEAD_LETTER = "fip.deadletter";

    // Queue Names
    public static final String AUDIT_QUEUE = "fip.audit.queue";
    public static final String AUTH_QUEUE = "fip.auth.queue";
    public static final String PLUGIN_QUEUE = "fip.plugin.queue";
    public static final String EVENT_QUEUE = "fip.event.queue";
    public static final String AI_QUEUE = "fip.ai.queue";
    public static final String DEAD_LETTER_QUEUE = "fip.dlq";
}
