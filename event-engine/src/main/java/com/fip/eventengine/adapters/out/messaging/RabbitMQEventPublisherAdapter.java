package com.fip.eventengine.adapters.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.eventengine.ports.out.EventPublishingPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitMQEventPublisherAdapter implements EventPublishingPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQEventPublisherAdapter.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitMQEventPublisherAdapter(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CanonicalEvent event) {
        try {
            String routingKey = resolveRoutingKey(event.eventType());
            String message = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(EventTopics.EXCHANGE, routingKey, message);
            log.info("Published canonical event [{}] with routingKey [{}]", event.eventId(), routingKey);
        } catch (Exception e) {
            log.error("Failed to publish canonical event [{}]: {}", event.eventId(), e.getMessage(), e);
        }
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) {
            return "financial.events.general";
        }
        if (eventType.startsWith("User")) {
            return EventTopics.ROUTING_KEY_AUTH;
        }
        if (eventType.startsWith("Plugin")) {
            return EventTopics.ROUTING_KEY_PLUGIN;
        }
        if (eventType.startsWith("AI") || eventType.startsWith("Doc")) {
            return EventTopics.ROUTING_KEY_AI;
        }
        return "financial.events." + eventType.toLowerCase();
    }
}
