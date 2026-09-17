package com.cinema.payment;

import jakarta.validation.Validator;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.payment.PaymentMessages.PaymentRequested;

@Component
public class PaymentListener {

    private final JsonMapper json;
    private final Validator validator;
    private final PaymentService service;

    public PaymentListener(
            JsonMapper json,
            Validator validator,
            PaymentService service) {
        this.json = json;
        this.validator = validator;
        this.service = service;
    }

    @RabbitListener(queues = RabbitConfiguration.REQUEST_QUEUE)
    public void receive(Message message) {
        PaymentRequested request;

        try {
            request = json.readValue(
                    message.getBody(), PaymentRequested.class
            );

            if (request == null || !validator.validate(request).isEmpty()) {
                throw new IllegalArgumentException(
                        "Neispravan sadržaj zahteva za plaćanje."
                );
            }
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "Poruka nije validan zahtev za plaćanje.", exception
            );
        }

        service.process(request);
    }
}