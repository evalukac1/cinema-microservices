package com.cinema.screening;

import java.sql.SQLException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDatabaseConflict(
            DataIntegrityViolationException exception) {

        String sqlState = null;
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                sqlState = sqlException.getSQLState();
                break;
            }
            cause = cause.getCause();
        }

        if ("23P01".equals(sqlState)) {
            return ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Projekcija se preklapa sa postojećom projekcijom u sali."
            );
        }

        if ("23505".equals(sqlState)) {
            return ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Zapis sa istom jedinstvenom vrednošću već postoji."
            );
        }

        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Podaci nisu usklađeni sa trenutnim stanjem baze."
        );
    }
}