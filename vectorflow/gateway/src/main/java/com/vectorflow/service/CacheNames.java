package com.vectorflow.service;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Cache names and key builders shared by the cache config and the cached services. */
public final class CacheNames {

    public static final String SEARCH = "searchResults";
    public static final String ASK = "askResults";

    private CacheNames() {}

    /** Canonical key: identical requests (including filters) hit the same entry; different filters never collide. */
    public static String key(String text, int limit, Map<String, Object> filters, Double minScore) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase();
        String filterPart = filters == null || filters.isEmpty() ? "-" : new TreeMap<>(filters).toString();
        return normalized + "|" + limit + "|" + filterPart + "|" + Objects.toString(minScore, "-");
    }
}
