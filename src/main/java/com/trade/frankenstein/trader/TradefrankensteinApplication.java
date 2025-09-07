package com.trade.frankenstein.trader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TradefrankensteinApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradefrankensteinApplication.class, args);
	}

}
