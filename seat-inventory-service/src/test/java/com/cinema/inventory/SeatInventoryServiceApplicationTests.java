package com.cinema.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;


@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatInventoryServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
