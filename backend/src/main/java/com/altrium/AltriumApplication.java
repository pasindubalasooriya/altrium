package com.altrium;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Altrium - Performance & Development Tracker.
 *
 * <p>Scheduling is enabled here for the daily cycle-opening sweep (P-6.4), which runs as a
 * system principal: it bypasses user authorization because there is no user, but remains
 * bound by every domain invariant.
 */
@SpringBootApplication
@EnableScheduling
public class AltriumApplication {

    public static void main(String[] args) {
        SpringApplication.run(AltriumApplication.class, args);
    }
}
