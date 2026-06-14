package com.aegis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aegis: a small reliability layer that fronts an AI-powered work-item triage
 * endpoint. It exists to demonstrate, end to end, the patterns a Backend
 * Reliability team owns: a distributed rate limiter, a circuit breaker around an
 * unreliable downstream (here, an LLM), distributed cache-aside with stampede
 * protection, RED metrics, and graceful degradation.
 */
@SpringBootApplication
public class AegisApplication {
    public static void main(String[] args) {
        SpringApplication.run(AegisApplication.class, args);
    }
}
