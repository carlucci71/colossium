package it.daniele.colossium;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ColossiumApplication {

	public static void main(String[] args) {
        SpringApplication.run(ColossiumApplication.class, args);
	}

}
