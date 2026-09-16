package com.cinema.inventory;

import org.springframework.boot.SpringApplication;

public class TestSeatInventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(SeatInventoryServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
