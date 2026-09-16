package com.cinema.screening;

import org.springframework.boot.SpringApplication;

public class TestScreeningServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ScreeningServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
