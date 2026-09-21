package com.acme.hrms.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Phase 0 deployable: auth-service + reference endpoints + SSO bridge, one Spring Boot process
 * scanning every hrms module (com.acme.hrms.*).
 */
@SpringBootApplication(scanBasePackages = "com.acme.hrms")
@ConfigurationPropertiesScan(basePackages = "com.acme.hrms")
public class HrmsApplication {
  public static void main(String[] args) {
    SpringApplication.run(HrmsApplication.class, args);
  }
}
