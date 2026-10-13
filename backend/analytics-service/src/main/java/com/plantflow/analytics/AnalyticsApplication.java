package com.plantflow.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages={"com.plantflow.analytics", "com.plantflow.shared"})
public class AnalyticsApplication {
    public static void main(String[] args) { SpringApplication.run(AnalyticsApplication.class,args); }
}
