package com.cinema.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduling
public class SeatInventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatInventoryServiceApplication.class, args);
	}

}
