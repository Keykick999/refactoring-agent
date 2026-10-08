package com.aicompany.agentharness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentHarnessApplication {

	public static void main(String[] args) {
		System.exit(SpringApplication.exit(SpringApplication.run(AgentHarnessApplication.class, args)));
	}

}
