package cc.ginpika.bootfs.service;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ClashProbeSecurityTest {
    @ParameterizedTest @ValueSource(strings={"127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.100.100.200", "198.18.0.1", "224.0.0.1", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1"})
    void specialAndInternalAddressesAreNotProbeTargets(String address) throws Exception {
        assertFalse(ClashLatencyService.publicAddress(InetAddress.getByName(address)));
    }
    @ParameterizedTest @ValueSource(strings={"1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"})
    void publicAddressesAreAllowed(String address) throws Exception {
        assertTrue(ClashLatencyService.publicAddress(InetAddress.getByName(address)));
    }
}
