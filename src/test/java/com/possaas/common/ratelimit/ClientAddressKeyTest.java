package com.possaas.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressKeyTest {
    @Test
    void ipv4IsKeptAsIs() {
        assertThat(ClientAddressKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddressKey.of(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6AddressesOfOneSlash64ShareAKey() {
        String first = ClientAddressKey.of("2001:db8:abcd:12:1::1");
        String second = ClientAddressKey.of("2001:0db8:abcd:0012:ffff:ffff:ffff:ffff");

        assertThat(first).isEqualTo("v6:2001:0db8:abcd:0012::/64").isEqualTo(second);
        assertThat(ClientAddressKey.of("2001:db8:abcd:13::1")).isNotEqualTo(first);
        assertThat(ClientAddressKey.of("[2001:db8:abcd:12::9]")).isEqualTo(first);
        assertThat(ClientAddressKey.of("fe80::1%eth0")).isEqualTo("v6:fe80:0000:0000:0000::/64");
        assertThat(ClientAddressKey.of("::1")).isEqualTo("v6:0000:0000:0000:0000::/64");
    }

    @Test
    void ipv4MappedIpv6CountsAsTheIpv4Address() {
        assertThat(ClientAddressKey.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void missingOrMalformedAddressesNeverFail() {
        assertThat(ClientAddressKey.of(null)).isEqualTo("unknown");
        assertThat(ClientAddressKey.of("  ")).isEqualTo("unknown");
        assertThat(ClientAddressKey.of("not:an:address")).isEqualTo("not:an:address");
        assertThat(ClientAddressKey.of("1:::2")).isEqualTo("1:::2");
    }
}
