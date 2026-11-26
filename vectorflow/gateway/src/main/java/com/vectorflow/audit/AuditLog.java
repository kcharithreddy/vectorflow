package com.vectorflow.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Security audit trail: one structured line per security-relevant event,
 * written to a dedicated "AUDIT" logger so it can be shipped/retained separately.
 *
 * <p>Example: {@code AUDIT event=LOGIN_FAILURE email=a@b.co}
 */
@Component
public class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** @param fields alternating key, value pairs */
    public void event(String event, Object... fields) {
        StringBuilder line = new StringBuilder("event=").append(event);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            line.append(' ').append(fields[i]).append('=').append(clean(fields[i + 1]));
        }
        AUDIT.info(line.toString());
    }

    /** Strip characters that could forge extra log lines (log injection). */
    static String clean(Object value) {
        if (value == null) {
            return "-";
        }
        String s = value.toString().replaceAll("[\\r\\n\\t]", "_");
        return s.contains(" ") ? "\"" + s.replace("\"", "'") + "\"" : s;
    }
}
