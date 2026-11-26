package com.vectorflow.web;

import java.net.InetAddress;

/**
 * Works out the real client IP.
 *
 * <p>X-Forwarded-For is only trusted when the direct peer is a private/loopback
 * address (i.e. our own nginx proxy). nginx <em>appends</em> the address it saw,
 * so the last entry is the one it vouches for; earlier entries are client-controlled.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(String remoteAddr, String forwardedFor) {
        if (forwardedFor != null && !forwardedFor.isBlank() && isTrustedProxy(remoteAddr)) {
            String[] parts = forwardedFor.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    static boolean isTrustedProxy(String addr) {
        if (addr == null || addr.isBlank() || !addr.matches("[0-9a-fA-F.:]+")) {
            return false; // only literal IPs - never trigger a DNS lookup
        }
        try {
            InetAddress ip = InetAddress.getByName(addr);
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
