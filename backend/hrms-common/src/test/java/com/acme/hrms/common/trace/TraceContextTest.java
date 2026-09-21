package com.acme.hrms.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceContextTest {
  @Test
  void parsesW3cTraceparent() {
    assertThat(
            TraceContext.fromTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))
        .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(TraceContext.fromTraceparent("garbage")).isNull();
    assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}");
  }
}
