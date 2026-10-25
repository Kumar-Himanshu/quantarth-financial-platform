package com.fip.auth.adapters.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.auth.ports.out.EventPublishingPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitMQPublisherAdapter implements EventPublishingPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQPublisherAdapter.class);
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitMQPublisherAdapter(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CanonicalEvent event) {
        try {
            String jsonEvent = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(
                    EventTopics.EXCHANGE,
                    EventTopics.ROUTING_KEY_AUTH,
                    jsonEvent);
            log.info("Successfully published auth event of type: {}", event.eventType());
        } catch (Exception e) {
            log.error("Failed to publish event to RabbitMQ", e);
        }
    }
}
