package com.mcap.minecraftagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.mcap.minecraftagent")
public class MinecraftAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinecraftAgentApplication.class, args);
	}

}
