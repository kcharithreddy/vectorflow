package com.vectorflow.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @Test
    void cleansValuesToPreventLogInjection() {
        assertThat(AuditLog.clean("evil\nevent=LOGIN_SUCCESS")).isEqualTo("evil_event=LOGIN_SUCCESS");
        assertThat(AuditLog.clean("two words")).isEqualTo("\"two words\"");
        assertThat(AuditLog.clean(null)).isEqualTo("-");
    }
}
