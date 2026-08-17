package com.flatmaite.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is for long-running server processes only. The seed profile is a one-shot CLI run
 * (web-application-type: none) — enabling scheduling there spawns a non-daemon scheduler thread
 * that prevents the JVM from ever exiting after seeding completes.
 */
@Configuration
@EnableScheduling
@Profile("!seed")
public class SchedulingConfig {}
