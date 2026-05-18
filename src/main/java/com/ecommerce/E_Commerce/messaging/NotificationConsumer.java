package com.ecommerce.E_Commerce.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @RabbitListener(queues = "${app.rabbitmq.notification-queue}")
    public void handle(OrderPlacedEvent event) {
        log.info("[NOTIFICATION] Sending confirmation email for order #{}", event.orderId());
        // In a real system: render template, call email/SMS provider.
    }
}
