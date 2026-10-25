package com.fip.eventengine.config;

import com.fip.shared.events.EventTopics;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange financialExchange() {
        return new TopicExchange(EventTopics.EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(EventTopics.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(EventTopics.AUDIT_QUEUE)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue authQueue() {
        return QueueBuilder.durable(EventTopics.AUTH_QUEUE)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue pluginQueue() {
        return QueueBuilder.durable(EventTopics.PLUGIN_QUEUE)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue eventQueue() {
        return QueueBuilder.durable(EventTopics.EVENT_QUEUE)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue aiQueue() {
        return QueueBuilder.durable(EventTopics.AI_QUEUE)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(EventTopics.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange financialExchange) {
        return BindingBuilder.bind(auditQueue).to(financialExchange).with(EventTopics.ROUTING_KEY_AUDIT);
    }

    @Bean
    public Binding authBinding(Queue authQueue, TopicExchange financialExchange) {
        return BindingBuilder.bind(authQueue).to(financialExchange).with(EventTopics.ROUTING_KEY_AUTH);
    }

    @Bean
    public Binding pluginBinding(Queue pluginQueue, TopicExchange financialExchange) {
        return BindingBuilder.bind(pluginQueue).to(financialExchange).with(EventTopics.ROUTING_KEY_PLUGIN);
    }

    @Bean
    public Binding eventBinding(Queue eventQueue, TopicExchange financialExchange) {
        return BindingBuilder.bind(eventQueue).to(financialExchange).with(EventTopics.ROUTING_KEY_EVENT);
    }

    @Bean
    public Binding aiBinding(Queue aiQueue, TopicExchange financialExchange) {
        return BindingBuilder.bind(aiQueue).to(financialExchange).with(EventTopics.ROUTING_KEY_AI);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
