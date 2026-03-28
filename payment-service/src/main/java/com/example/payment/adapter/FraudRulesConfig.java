package com.example.payment.adapter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.example.common.adapter.FraudRulesProperties;

@Configuration
@EnableConfigurationProperties({ FraudRulesProperties.class, BenchmarkProperties.class, ScyllaProperties.class })
public class FraudRulesConfig {
}
