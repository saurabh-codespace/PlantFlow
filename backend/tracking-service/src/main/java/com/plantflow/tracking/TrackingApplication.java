package com.plantflow.tracking;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages={"com.plantflow.tracking","com.plantflow.shared"})
@EnableScheduling
public class TrackingApplication { public static void main(String[] args) { SpringApplication.run(TrackingApplication.class,args); } }
