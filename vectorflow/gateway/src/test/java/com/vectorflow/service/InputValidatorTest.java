package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputValidatorTest {

    @Test
    void normalizesEmail() {
        assertThat(InputValidator.normalizeEmail("  Foo@Example.COM ")).isEqualTo("foo@example.com");
    }

    @Test
    void rejectsInvalidEmails() {
        for (String bad : new String[]{null, "", "nope", "a@b", "a b@c.d"}) {
            assertThatThrownBy(() -> InputValidator.normalizeEmail(bad)).isInstanceOf(GatewayException.class);
        }
    }

    @Test
    void passwordLengthBounds() {
        assertThatThrownBy(() -> InputValidator.validatePassword("short")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> InputValidator.validatePassword("x".repeat(73))).isInstanceOf(GatewayException.class);
        InputValidator.validatePassword("longenough1");
    }

    @Test
    void passwordPolicy() {
        assertThatThrownBy(() -> InputValidator.validatePassword("onlyletters")).hasMessageContaining("letter and one number");
        assertThatThrownBy(() -> InputValidator.validatePassword("1234567890")).hasMessageContaining("letter and one number");
        assertThatThrownBy(() -> InputValidator.validatePassword("Password123")).hasMessageContaining("too common");
        assertThatThrownBy(() -> InputValidator.validatePassword("charith2026", "charith@example.com")).hasMessageContaining("email name");
        InputValidator.validatePassword("blue-river-42", "charith@example.com");
    }

    @Test
    void rangeUsesDefaultAndEnforcesBounds() {
        assertThat(InputValidator.range(null, 5, 1, 20, "limit")).isEqualTo(5);
        assertThatThrownBy(() -> InputValidator.range(21, 5, 1, 20, "limit"))
                .hasMessageContaining("between 1 and 20");
    }

    @Test
    void requireTextTrimsAndRejectsBlank() {
        assertThat(InputValidator.requireText("  hi ", "title", 10)).isEqualTo("hi");
        assertThatThrownBy(() -> InputValidator.requireText("  ", "title", 10)).hasMessageContaining("title");
        assertThatThrownBy(() -> InputValidator.requireText("x".repeat(11), "title", 10)).hasMessageContaining("at most");
    }

    @Test
    void uuidValidation() {
        assertThatThrownBy(() -> InputValidator.uuid("nope", "documentId")).hasMessageContaining("UUID");
        assertThat(InputValidator.uuid("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", "id")).isNotNull();
    }
}
