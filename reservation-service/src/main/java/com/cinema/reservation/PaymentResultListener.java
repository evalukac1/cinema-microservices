package com.cinema.reservation;

import jakarta.validation.Validator;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.reservation.ReservationModels.PaymentResult;

@Component
public class PaymentResultListener {

    private final JsonMapper json;
    private final Validator validator;
    private final PaymentResultService service;

    public PaymentResultListener(
            JsonMapper json,
            Validator validator,
            PaymentResultService service) {
        this.json = json;
        this.validator = validator;
        this.service = service;
    }

    @RabbitListener(queues = RabbitConfiguration.RESULT_QUEUE)
    public void receive(Message message) {
        PaymentResult event;

        try {
            event = json.readValue(message.getBody(), PaymentResult.class);

            if (event == null || !validator.validate(event).isEmpty()) {
                throw new IllegalArgumentException(
                        "Neispravan rezultat plaćanja."
                );
            }
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "Poruka nije validan rezultat plaćanja.", exception
            );
        }

        service.process(event);
    }
}