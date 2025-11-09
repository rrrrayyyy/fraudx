package com.example.payment_service.adapter;

import java.util.HashMap;

import org.apache.kafka.clients.admin.*;
import org.springframework.context.annotation.*;

@Configuration
public class KafkaAdminConfig {
	private final String bootstrapServers;

	public KafkaAdminConfig(String bootstrapServers) {
		this.bootstrapServers = bootstrapServers;
	}

	@Bean
	public AdminClient adminClient() {
		var configs = new HashMap<String, Object>();
		configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		return AdminClient.create(configs);
	}
}
