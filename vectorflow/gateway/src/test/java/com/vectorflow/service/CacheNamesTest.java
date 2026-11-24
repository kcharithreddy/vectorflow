package com.vectorflow.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheNamesTest {

    @Test
    void normalizesWhitespaceAndCase() {
        assertThat(CacheNames.key("  Docker   Containers ", 5, null, null))
                .isEqualTo(CacheNames.key("docker containers", 5, Map.of(), null));
    }

    @Test
    void differentFiltersLimitsAndScoresProduceDifferentKeys() {
        String base = CacheNames.key("q", 5, null, null);
        assertThat(CacheNames.key("q", 5, Map.of("category", "a"), null)).isNotEqualTo(base);
        assertThat(CacheNames.key("q", 6, null, null)).isNotEqualTo(base);
        assertThat(CacheNames.key("q", 5, null, 0.5)).isNotEqualTo(base);
    }

    @Test
    void filterOrderDoesNotMatter() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("category", "x");
        a.put("tags", List.of("t"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tags", List.of("t"));
        b.put("category", "x");
        assertThat(CacheNames.key("q", 5, a, null)).isEqualTo(CacheNames.key("q", 5, b, null));
    }
}
