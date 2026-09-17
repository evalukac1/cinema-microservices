package com.cinema.reservation;

import jakarta.validation.Validator;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.cinema.reservation.RefundMessages.RefundResult;

@Component
public class RefundResultListener {

    private final JsonMapper json;
    private final Validator validator;
    private final ReservationRefundService service;

    public RefundResultListener(
            JsonMapper json,
            Validator validator,
            ReservationRefundService service) {
        this.json = json;
        this.validator = validator;
        this.service = service;
    }

    @RabbitListener(queues = RefundRabbitConfiguration.RESULT_QUEUE)
    public void receive(Message message) {
        RefundResult event;

        try {
            event = json.readValue(
                    message.getBody(), RefundResult.class
            );

            if (event == null || !validator.validate(event).isEmpty()) {
                throw new IllegalArgumentException(
                        "Neispravan sadržaj rezultata povraćaja."
                );
            }
        } catch (Exception exception) {
            throw new AmqpRejectAndDontRequeueException(
                    "Poruka nije validan rezultat povraćaja.",
                    exception
            );
        }

        service.processResult(event);
    }
}