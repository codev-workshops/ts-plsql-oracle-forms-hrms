package com.acme.hrms.admin;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminConfig {
  @Bean(name = "leaveJobExecutor")
  @ConditionalOnMissingBean(name = "leaveJobExecutor")
  public Executor leaveJobExecutor() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "leave-job");
          t.setDaemon(true);
          return t;
        });
  }
}
