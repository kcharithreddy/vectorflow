package com.vectorflow.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document metadata used for filtering (category + tags).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Metadata {

    private String category;
    private List<String> tags;

    /** Map form sent to the Python service and stored in the documents.metadata JSONB column. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (category != null && !category.isBlank()) {
            map.put("category", category.trim());
        }
        if (tags != null && !tags.isEmpty()) {
            map.put("tags", tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList());
        }
        return map;
    }
}
