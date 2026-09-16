package com.cinema.reservation;

import org.springframework.boot.SpringApplication;

public class TestReservationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ReservationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
