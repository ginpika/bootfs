package cc.ginpika.bootfs.kernel.cluster;

import com.alibaba.fastjson2.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Opt-in LAN discovery. A shared pairing token authenticates beacons; it is never broadcast. */
final class LanDiscovery implements AutoCloseable {
    private final ClusterCoordinator coordinator;
    private final String token;
    private final MulticastSocket socket;
    private final InetAddress group = InetAddress.getByName("239.192.42.66");
    private final int port;
    private final List<NetworkInterface> interfaces = new ArrayList<>();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LanDiscovery.class);
    private volatile boolean closed;
    LanDiscovery(ClusterCoordinator coordinator, String token, int port) throws IOException {
        this.coordinator = coordinator; this.token = token; this.port = port;
        socket = new MulticastSocket(null); socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"),port));
        socket.setTimeToLive(1); socket.setLoopbackMode(false); socket.setSoTimeout(1000);
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || (!network.supportsMulticast() && !network.isLoopback())
                    || Collections.list(network.getInetAddresses()).stream().noneMatch(address -> address instanceof Inet4Address)) continue;
            try { socket.joinGroup(new InetSocketAddress(group,port),network); interfaces.add(network); }
            catch (IOException unavailable) { log.debug("Discovery interface {} is unavailable",network.getName(),unavailable); }
        }
        if (interfaces.isEmpty()) { socket.close(); throw new IOException("No IPv4 multicast interface; use manual connect"); }
        log.info("LAN discovery active on {}",interfaces.stream().map(NetworkInterface::getName).toList());
        Thread receiver = new Thread(this::receive, "bootfs-lan-discovery"); receiver.setDaemon(true); receiver.start();
    }
    private byte[] sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    void announce(JSONObject status) throws IOException {
        JSONObject beacon = new JSONObject();
        for (String key : List.of("nodeId", "groupId", "phase", "endpoint", "independent")) beacon.put(key, status.get(key));
        beacon.put("time", System.currentTimeMillis());
        String payload = beacon.toJSONString();
        try {
            JSONObject envelope = new JSONObject(); envelope.put("payload", payload); envelope.put("signature", HexFormat.of().formatHex(sign(payload)));
            byte[] bytes = envelope.toJSONString().getBytes(StandardCharsets.UTF_8);
            IOException last = null; boolean sent = false;
            for (NetworkInterface network : interfaces) {
                try { socket.setNetworkInterface(network); socket.send(new DatagramPacket(bytes,bytes.length,group,port)); sent = true; }
                catch (IOException unavailable) { last = unavailable; }
            }
            if (!sent) throw last == null ? new IOException("No discovery interface is available") : last;
        } catch (GeneralSecurityException failure) { throw new IOException("Cannot authenticate discovery beacon", failure); }
    }
    private void receive() {
        while (!closed) {
            try {
                byte[] buffer = new byte[4096]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                JSONObject envelope = JSON.parseObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                String payload = envelope.getString("payload");
                if (payload == null || !MessageDigest.isEqual(sign(payload), HexFormat.of().parseHex(envelope.getString("signature")))) continue;
                JSONObject beacon = JSON.parseObject(payload);
                if (Math.abs(System.currentTimeMillis() - beacon.getLongValue("time")) > 15000) continue;
                if (!Objects.equals(coordinator.dynamic().status().getString("nodeId"), beacon.getString("nodeId"))) coordinator.discovered(beacon);
            } catch (Exception ignored) { /* Invalid, stale or unauthenticated packets do not alter membership. */ }
        }
    }
    @Override public void close() { closed = true; socket.close(); }
}
