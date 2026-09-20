package com.cinema.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/unavailable")
    public ResponseEntity<ProblemDetail> unavailable() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servis trenutno nije dostupan ili odgovor nije stigao "
                        + "na vreme. Ako ste poslali zahtev za promenu, "
                        + "proverite njegov status pre ponavljanja."
        );

        problem.setTitle("Servis privremeno nedostupan");
        problem.setProperty("code", "SERVICE_UNAVAILABLE");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem);
    }
}