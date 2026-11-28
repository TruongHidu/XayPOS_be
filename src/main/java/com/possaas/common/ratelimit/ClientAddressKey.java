package com.possaas.common.ratelimit;

import java.util.ArrayList;
import java.util.List;

/**
 * Rate-limit key for a client address. IPv4 is used as is; IPv6 is grouped by its /64 prefix because a single
 * subscriber usually controls a whole /64 and could otherwise rotate through endless addresses. Parsing is done
 * by hand on purpose: {@code InetAddress.getByName} falls back to a DNS lookup for strings that are not valid
 * literals, and the address may come from a forwarded header.
 */
public final class ClientAddressKey {
    static final String UNKNOWN = "unknown";

    private ClientAddressKey() {}

    public static String of(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) return UNKNOWN;
        String address = remoteAddress.trim();
        if (address.indexOf(':') < 0) return address;
        int[] hextets = parseIpv6(address);
        if (hextets == null) return address;
        if (isIpv4Mapped(hextets)) {
            return (hextets[6] >> 8) + "." + (hextets[6] & 0xff) + "." + (hextets[7] >> 8) + "." + (hextets[7] & 0xff);
        }
        return "v6:%04x:%04x:%04x:%04x::/64".formatted(hextets[0], hextets[1], hextets[2], hextets[3]);
    }

    private static boolean isIpv4Mapped(int[] hextets) {
        for (int i = 0; i < 5; i++) if (hextets[i] != 0) return false;
        return hextets[5] == 0xffff;
    }

    /** Eight 16-bit groups, or null when the text is not an IPv6 literal. */
    private static int[] parseIpv6(String text) {
        String literal = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        int zone = literal.indexOf('%');
        if (zone >= 0) literal = literal.substring(0, zone);
        int gap = literal.indexOf("::");
        if (gap != literal.lastIndexOf("::")) return null;

        List<Integer> head = gap < 0 ? groups(literal) : groups(literal.substring(0, gap));
        List<Integer> tail = gap < 0 ? List.of() : groups(literal.substring(gap + 2));
        if (head == null || tail == null) return null;
        int present = head.size() + tail.size();
        if (gap < 0 ? present != 8 : present > 7) return null;

        int[] hextets = new int[8];
        for (int i = 0; i < head.size(); i++) hextets[i] = head.get(i);
        for (int i = 0; i < tail.size(); i++) hextets[8 - tail.size() + i] = tail.get(i);
        return hextets;
    }

    /** Colon-separated groups; a trailing dotted IPv4 counts as two groups. Null when malformed. */
    private static List<Integer> groups(String part) {
        List<Integer> groups = new ArrayList<>();
        if (part.isEmpty()) return groups;
        String[] tokens = part.split(":", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (i == tokens.length - 1 && token.indexOf('.') >= 0) {
                int[] ipv4 = parseIpv4(token);
                if (ipv4 == null) return null;
                groups.add((ipv4[0] << 8) | ipv4[1]);
                groups.add((ipv4[2] << 8) | ipv4[3]);
                continue;
            }
            if (token.isEmpty() || token.length() > 4) return null;
            int value = 0;
            for (int c = 0; c < token.length(); c++) {
                int digit = Character.digit(token.charAt(c), 16);
                if (digit < 0) return null;
                value = (value << 4) | digit;
            }
            groups.add(value);
        }
        return groups;
    }

    private static int[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return null;
            int value = 0;
            for (int c = 0; c < part.length(); c++) {
                int digit = Character.digit(part.charAt(c), 10);
                if (digit < 0) return null;
                value = value * 10 + digit;
            }
            if (value > 255) return null;
            octets[i] = value;
        }
        return octets;
    }
}
