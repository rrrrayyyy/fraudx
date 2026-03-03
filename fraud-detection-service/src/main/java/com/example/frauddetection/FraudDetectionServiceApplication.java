package com.example.frauddetection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.example.frauddetection")
public class FraudDetectionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(FraudDetectionServiceApplication.class, args);
	}

}
