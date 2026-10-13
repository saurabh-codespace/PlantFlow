package com.plantflow.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages={"com.plantflow.fleet", "com.plantflow.shared"})
public class FleetApplication {
    public static void main(String[] args) { SpringApplication.run(FleetApplication.class, args); }
}
