package com.plantflow.transport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages={"com.plantflow.transport","com.plantflow.shared"})
@EnableScheduling
public class TransportApplication { public static void main(String[] args) { SpringApplication.run(TransportApplication.class,args); } }
