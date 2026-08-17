package com.altrium.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The system clock, as a bean.
 *
 * <p>Injected rather than reached through {@code LocalDate.now()} so the one piece of Altrium
 * that behaves differently depending on the date - the cycle sweep (P-6.4) - can be tested by
 * putting the clock somewhere rather than by waiting. Without this, "a past-dated cycle opens
 * on the next sweep" is not a test anybody can write.
 *
 * <p>{@link ConditionalOnMissingBean} so a test can supply a fixed clock and have it win.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
