package com.acme.hrms.auth.sso;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class SsoBridgeConfig {

  /** {@code hrms.legacy.*}: Oracle coordinates (read-only + PKG_SECURITY) and the proxy CIDRs. */
  @ConfigurationProperties(prefix = "hrms.legacy")
  public record LegacyProperties(Oracle oracle, @DefaultValue("") List<String> proxyCidrs) {
    public record Oracle(
        @Nullable String url, @Nullable String username, @Nullable String password) {}
  }

  @Bean
  public Optional<LegacySessionGateway> legacySessionGateway(LegacyProperties props) {
    if (props.oracle() == null || props.oracle().url() == null || props.oracle().url().isBlank()) {
      return Optional.empty();
    }
    DataSource ds =
        DataSourceBuilder.create()
            .url(props.oracle().url())
            .username(props.oracle().username())
            .password(props.oracle().password())
            .build();
    return Optional.of(new OracleLegacySessionGateway(ds));
  }

  @Bean
  public SsoBridgeService ssoBridgeService(
      Optional<LegacySessionGateway> gateway,
      com.acme.hrms.auth.proxy.ProxyFlags flags,
      LegacyProperties props,
      java.time.Clock clock) {
    return new SsoBridgeService(gateway.orElse(null), flags, props.proxyCidrs(), clock);
  }
}
