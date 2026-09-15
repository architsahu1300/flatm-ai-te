package com.flatmaite.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is for long-running server processes only. The seed and eval profiles are batch
 * CLI runs (web-application-type: none) — enabling scheduling there spawns a non-daemon
 * scheduler thread that prevents the JVM from ever exiting after the batch completes.
 */
@Configuration
@EnableScheduling
@Profile("!seed & !eval")
public class SchedulingConfig {}
