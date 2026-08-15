package com.armsone.nasfinder.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebHardLanAddressTest {
    private fun address(value: String) = InetAddress.getByName(value) as java.net.Inet4Address

    @Test
    fun `only RFC1918 LAN addresses are advertised`() {
        assertTrue(isAdvertisableWebHardAddress("wlan0", address("10.1.2.3")))
        assertTrue(isAdvertisableWebHardAddress("wlan0", address("172.16.0.1")))
        assertTrue(isAdvertisableWebHardAddress("ap0", address("172.31.255.254")))
        assertTrue(isAdvertisableWebHardAddress("p2p0", address("192.168.0.199")))

        assertFalse(isAdvertisableWebHardAddress("v4-rmnet0", address("192.0.0.4")))
        assertFalse(isAdvertisableWebHardAddress("rmnet0", address("172.32.0.1")))
        assertFalse(isAdvertisableWebHardAddress("rmnet0", address("100.64.0.1")))
        assertFalse(isAdvertisableWebHardAddress("lo", address("127.0.0.1")))
        assertFalse(isAdvertisableWebHardAddress("wlan0", address("169.254.1.1")))
    }

    @Test
    fun `VPN interfaces may advertise non LAN addresses but never protocol assignment range`() {
        assertTrue(isAdvertisableWebHardAddress("tun0", address("100.64.0.10")))
        assertTrue(isAdvertisableWebHardAddress("tailscale0", address("100.100.10.20")))
        assertTrue(isAdvertisableWebHardAddress("wg0", address("203.0.113.7")))
        assertFalse(isAdvertisableWebHardAddress("tun0", address("192.0.0.4")))
    }
}
