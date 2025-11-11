package com.example.fraud_detection_service.adapter;

import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component
public class KafkaStartupLogger {
    private static final Logger log = LoggerFactory.getLogger(KafkaStartupLogger.class);
    private final KafkaListenerEndpointRegistry registry;

    public KafkaStartupLogger(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logKafkaListenerStartup() {
        registry.getListenerContainers().forEach(container -> {
            var listenerId = container.getListenerId();
            var running = container.isRunning();
            log.info("✅ Kafka listener [{}] started: {}", listenerId, running);
            if (running) {
                System.out.println("✅ assigned partitions:" + container.getAssignedPartitions());
                container.getAssignedPartitions().forEach(p -> log.info("  -> assigned partition: {}", p));
            }
        });
    }
}
