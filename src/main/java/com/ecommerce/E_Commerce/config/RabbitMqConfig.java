package com.ecommerce.E_Commerce.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.invoice-queue}")
    private String invoiceQueue;

    @Value("${app.rabbitmq.notification-queue}")
    private String notificationQueue;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Bean
    TopicExchange ordersExchange() {
        return new TopicExchange(exchange);
    }

    @Bean
    Queue invoiceQueue() {
        return new Queue(invoiceQueue, true);
    }

    @Bean
    Queue notificationQueue() {
        return new Queue(notificationQueue, true);
    }

    @Bean
    Binding invoiceBinding(Queue invoiceQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(invoiceQueue).to(ordersExchange).with(routingKey);
    }

    @Bean
    Binding notificationBinding(Queue notificationQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(notificationQueue).to(ordersExchange).with(routingKey);
    }

    @Bean
    Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
