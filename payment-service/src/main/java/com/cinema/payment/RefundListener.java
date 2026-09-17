package com.cinema.payment;

import jakarta.validation.Validator;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.payment.RefundMessages.RefundRequested;

@Component
public class RefundListener {

    private final JsonMapper json;
    private final Validator validator;
    private final RefundService service;

    public RefundListener(
            JsonMapper json,
            Validator validator,
            RefundService service) {
        this.json = json;
        this.validator = validator;
        this.service = service;
    }

    @RabbitListener(queues = RefundRabbitConfiguration.REQUEST_QUEUE)
    public void receive(Message message) {
        RefundRequested request;

        try {
            request = json.readValue(
                    message.getBody(), RefundRequested.class
            );

            if (request == null || !validator.validate(request).isEmpty()) {
                throw new IllegalArgumentException(
                        "Neispravan sadržaj zahteva za povraćaj."
                );
            }
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "Poruka nije validan zahtev za povraćaj.",
                    exception
            );
        }

        service.process(request);
    }
}