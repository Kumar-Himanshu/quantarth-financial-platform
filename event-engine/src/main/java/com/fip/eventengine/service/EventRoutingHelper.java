package com.fip.eventengine.service;

import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;

public final class EventRoutingHelper {

    private EventRoutingHelper() {
        // Utility class
    }

    /**
     * Resolves normalized canonical RabbitMQ routing keys.
     * <p>
     * Canonical Financial Event pattern: {@code financial.events.<entityType>.<action>}
     * Examples:
     * - PaymentInitiated -> financial.events.payment.initiated
     * - PaymentCaptured -> financial.events.payment.captured
     * - InvoiceCreated -> financial.events.invoice.created
     * - RefundInitiated -> financial.events.refund.initiated
     * <p>
     * Backwards-compatibility rules for platform events:
     * - User... / AUTH -> auth.events (EventTopics.ROUTING_KEY_AUTH)
     * - Plugin... / PLUGIN -> plugin.events (EventTopics.ROUTING_KEY_PLUGIN)
     * - AI... / Doc... / AI -> ai.events (EventTopics.ROUTING_KEY_AI)
     */
    public static String resolveRoutingKey(String entityType, String eventType) {
        if (eventType == null) {
            return "financial.events.general";
        }

        // 1. Preserve existing platform-level routing keys
        if (eventType.startsWith("User") || "AUTH".equalsIgnoreCase(entityType)) {
            return EventTopics.ROUTING_KEY_AUTH;
        }
        if (eventType.startsWith("Plugin") || "PLUGIN".equalsIgnoreCase(entityType)) {
            return EventTopics.ROUTING_KEY_PLUGIN;
        }
        if (eventType.startsWith("AI") || eventType.startsWith("Doc") || "AI".equalsIgnoreCase(entityType)) {
            return EventTopics.ROUTING_KEY_AI;
        }

        // 2. Canonical financial routing: financial.events.<entityType>.<action>
        String entity = (entityType != null && !entityType.isBlank())
                ? entityType.toLowerCase()
                : CanonicalEvent.inferEntityType(eventType).toLowerCase();

        String action = extractAction(eventType, entity);
        return "financial.events." + entity + "." + action;
    }

    public static String extractAction(String eventType, String entity) {
        if (eventType == null || eventType.isBlank()) {
            return "general";
        }
        // Normalize underscores and dots
        String cleaned = eventType.replace('_', '.').replace('-', '.');
        if (cleaned.contains(".")) {
            String[] parts = cleaned.split("\\.");
            return parts[parts.length - 1].toLowerCase();
        }

        // Remove entity prefix if eventType is camelCase like "PaymentInitiated" -> "initiated"
        String lowerEvent = eventType.toLowerCase();
        String lowerEntity = entity.replace("_", "");
        if (lowerEvent.startsWith(lowerEntity) && lowerEvent.length() > lowerEntity.length()) {
            return lowerEvent.substring(lowerEntity.length());
        }

        return lowerEvent;
    }
}
