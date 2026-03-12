package ru.quipy.common.utils

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class CircuitBreakerConfig {

    @Bean
    fun circuitBreaker(): CircuitBreaker {
        val circuitBreakerConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(40)
            .failureRateThreshold(70f)
            .waitDurationInOpenState(Duration.ofSeconds(1))
            .build()

        return CircuitBreaker.of("default", circuitBreakerConfig)
    }
}