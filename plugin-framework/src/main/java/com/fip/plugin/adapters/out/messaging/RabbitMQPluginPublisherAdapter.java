package com.fip.plugin.adapters.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.plugin.ports.out.PluginEventPublisherPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitMQPluginPublisherAdapter implements PluginEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQPluginPublisherAdapter.class);
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitMQPluginPublisherAdapter(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CanonicalEvent event) {
        try {
            String jsonEvent = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(
                    EventTopics.EXCHANGE,
                    EventTopics.ROUTING_KEY_PLUGIN,
                    jsonEvent);
            log.info("Successfully published plugin event of type: {}", event.eventType());
        } catch (Exception e) {
            log.error("Failed to publish plugin event to RabbitMQ", e);
        }
    }
}
