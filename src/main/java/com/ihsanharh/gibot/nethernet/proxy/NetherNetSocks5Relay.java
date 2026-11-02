package com.ihsanharh.gibot.nethernet.proxy;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transparent SOCKS5 UDP relay for NetherNet WebRTC sessions.
 * <p>
 * Because libdatachannel's native ICE backend (libjuice) ignores SOCKS5 proxies
 * for direct WebRTC peer connections, this relay establishes a local UDP endpoint
 * on 127.0.0.1, associates with the upstream SOCKS5 proxy via RFC 1928 UDP ASSOCIATE,
 * and transparently forwards bidirectional WebRTC (STUN, DTLS, SCTP) traffic so that
 * the server observes the residential proxy egress IP rather than a datacenter IP.
 */
@Log4j2
@Getter
public class NetherNetSocks5Relay implements Closeable {
    private final Socket tcpControlSocket;
    private final InetSocketAddress proxyRelayAddress;
    private final DatagramSocket localSocket;
    private final DatagramSocket proxySocket;
    private final int localPort;

    @Setter
    private volatile InetSocketAddress targetServerAddress;
    private final AtomicReference<SocketAddress> clientAddress = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong totalBytesSent = new AtomicLong();
    private final AtomicLong totalBytesReceived = new AtomicLong();

    private NetherNetSocks5Relay(Socket tcpControlSocket, InetSocketAddress proxyRelayAddress) throws IOException {
        this.tcpControlSocket = tcpControlSocket;
        this.proxyRelayAddress = proxyRelayAddress;

        // Local socket on loopback for libdatachannel communication
        this.localSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.localSocket.setReceiveBufferSize(2 * 1024 * 1024);
        this.localSocket.setSendBufferSize(2 * 1024 * 1024);
        this.localPort = this.localSocket.getLocalPort();

        // Socket for communication with upstream SOCKS5 UDP relay
        this.proxySocket = new DatagramSocket();
        this.proxySocket.setReceiveBufferSize(2 * 1024 * 1024);
        this.proxySocket.setSendBufferSize(2 * 1024 * 1024);

        Thread localToProxyThread = new Thread(this::forwardLocalToProxy, "nethernet-socks5-local-to-proxy");
        localToProxyThread.setDaemon(true);
        localToProxyThread.start();

        Thread proxyToLocalThread = new Thread(this::forwardProxyToLocal, "nethernet-socks5-proxy-to-local");
        proxyToLocalThread.setDaemon(true);
        proxyToLocalThread.start();
    }

    public static NetherNetSocks5Relay create(String proxyUrl) throws IOException {
        String normalized = proxyUrl.trim();
        if (!normalized.contains("://")) {
            normalized = "socks5://" + normalized;
        }

        URI uri = URI.create(normalized);
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : 1080;

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] parts = userInfo.split(":", 2);
            username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length > 1) {
                password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }

        log.info("Connecting to SOCKS5 proxy control at {}:{} for NetherNet UDP relay...", host, port);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(10000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        // 1. SOCKS5 Greeting (RFC 1928)
        boolean hasAuth = username != null && !username.isEmpty();
        if (hasAuth) {
            out.write(new byte[]{0x05, 0x01, 0x02}); // METHOD: USERNAME/PASSWORD (0x02)
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00}); // METHOD: NO AUTH (0x00)
        }
        out.flush();

        byte[] greetingResp = in.readNBytes(2);
        if (greetingResp.length < 2 || greetingResp[0] != 0x05) {
            socket.close();
            throw new IOException("Invalid SOCKS5 greeting response from proxy: " + (greetingResp.length > 0 ? greetingResp[0] : "empty"));
        }

        if (greetingResp[1] == 0x02) {
            // 2. Username/Password Authentication (RFC 1929)
            byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream authBuf = new ByteArrayOutputStream();
            authBuf.write(0x01); // Auth version
            authBuf.write(userBytes.length);
            authBuf.write(userBytes);
            authBuf.write(passBytes.length);
            authBuf.write(passBytes);

            out.write(authBuf.toByteArray());
            out.flush();

            byte[] authResp = in.readNBytes(2);
            if (authResp.length < 2 || authResp[1] != 0x00) {
                socket.close();
                throw new IOException("SOCKS5 proxy authentication failed (status: " + (authResp.length >= 2 ? authResp[1] : -1) + ")");
            }
            log.info("SOCKS5 proxy authentication successful.");
        } else if (greetingResp[1] != 0x00) {
            socket.close();
            throw new IOException("SOCKS5 proxy rejected authentication method: " + greetingResp[1]);
        }

        // 3. UDP ASSOCIATE Request (RFC 1928)
        // CMD=0x03, RSV=0x00, ATYP=0x01 (IPv4 0.0.0.0:0)
        out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        out.flush();

        // 4. UDP ASSOCIATE Response
        byte[] header = in.readNBytes(4);
        if (header.length < 4 || header[0] != 0x05) {
            socket.close();
            throw new IOException("Invalid SOCKS5 UDP ASSOCIATE header response");
        }
        if (header[1] != 0x00) {
            socket.close();
            throw new IOException("SOCKS5 proxy failed UDP ASSOCIATE request with error code: 0x" + Integer.toHexString(header[1] & 0xFF));
        }

        byte atyp = header[3];
        String bndHost;
        if (atyp == 0x01) { // IPv4
            byte[] ipBytes = in.readNBytes(4);
            bndHost = InetAddress.getByAddress(ipBytes).getHostAddress();
        } else if (atyp == 0x03) { // Domain
            int len = in.read();
            byte[] domainBytes = in.readNBytes(len);
            bndHost = new String(domainBytes, StandardCharsets.UTF_8);
        } else if (atyp == 0x04) { // IPv6
            byte[] ip6Bytes = in.readNBytes(16);
            bndHost = InetAddress.getByAddress(ip6Bytes).getHostAddress();
        } else {
            socket.close();
            throw new IOException("Unsupported address type in UDP ASSOCIATE response: " + atyp);
        }

        byte[] portBytes = in.readNBytes(2);
        int bndPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

        // If the proxy responds with 0.0.0.0, use the proxy server's connected IP address
        if ("0.0.0.0".equals(bndHost) || "::".equals(bndHost)) {
            bndHost = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress();
        }

        InetSocketAddress relay = new InetSocketAddress(bndHost, bndPort);
        log.info("SOCKS5 UDP Relay endpoint successfully bound at {}", relay);

        // Keep the TCP socket open indefinitely as the association anchor
        socket.setSoTimeout(0);
        socket.setKeepAlive(true);
        return new NetherNetSocks5Relay(socket, relay);
    }

    private void forwardLocalToProxy() {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                localSocket.receive(packet);
                clientAddress.set(packet.getSocketAddress());

                InetSocketAddress target = targetServerAddress;
                if (target == null) {
                    log.debug("Dropping packet from client {} because targetServerAddress is not yet set", packet.getSocketAddress());
                    continue;
                }

                int len = packet.getLength();
                byte[] targetIp = target.getAddress().getAddress();
                int targetPort = target.getPort();

                // SOCKS5 UDP Request Header (RFC 1928 Section 7):
                // +----+------+------+----------+----------+----------+
                // |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
                // +----+------+------+----------+----------+----------+
                // | 2  |  1   |  1   | Variable |    2     | Variable |
                // +----+------+------+----------+----------+----------+
                byte[] outBuf = new byte[10 + len];
                outBuf[0] = 0; // RSV
                outBuf[1] = 0; // RSV
                outBuf[2] = 0; // FRAG (standalone datagram)
                outBuf[3] = 1; // ATYP IPv4
                System.arraycopy(targetIp, 0, outBuf, 4, 4);
                outBuf[8] = (byte) ((targetPort >> 8) & 0xFF);
                outBuf[9] = (byte) (targetPort & 0xFF);
                System.arraycopy(packet.getData(), packet.getOffset(), outBuf, 10, len);

                totalBytesSent.addAndGet(outBuf.length);
                DatagramPacket proxyPacket = new DatagramPacket(outBuf, outBuf.length, proxyRelayAddress);
                proxySocket.send(proxyPacket);
                log.debug("Forwarded {}b UDP from client {} -> proxy {} (target: {})",
                        len, packet.getSocketAddress(), proxyRelayAddress, target);
            } catch (IOException e) {
                if (closed.get()) break;
                log.warn("Error forwarding local UDP packet to SOCKS5 relay: {}", e.getMessage());
            }
        }
    }

    private void forwardProxyToLocal() {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                proxySocket.receive(packet);
                int len = packet.getLength();
                log.debug("Received {}b UDP from proxy relay {}", len, packet.getSocketAddress());
                if (len < 10) continue;

                byte[] data = packet.getData();
                int offset = packet.getOffset();

                byte atyp = data[offset + 3];
                int headerLen;
                if (atyp == 1) { // IPv4: 4 bytes IP + 2 bytes port
                    headerLen = 10;
                } else if (atyp == 3) { // Domain: 1 byte len + domain + 2 bytes port
                    int domainLen = data[offset + 4] & 0xFF;
                    headerLen = 7 + domainLen;
                } else if (atyp == 4) { // IPv6: 16 bytes IP + 2 bytes port
                    headerLen = 22;
                } else {
                    continue;
                }

                int payloadLen = len - headerLen;
                if (payloadLen <= 0) continue;

                SocketAddress client = clientAddress.get();
                if (client != null) {
                    totalBytesReceived.addAndGet(payloadLen);
                    DatagramPacket localPacket = new DatagramPacket(data, offset + headerLen, payloadLen, client);
                    localSocket.send(localPacket);
                    log.debug("Forwarded {}b UDP payload to local client {}", payloadLen, client);
                }
            } catch (IOException e) {
                if (closed.get()) break;
                log.warn("Error forwarding SOCKS5 UDP packet to local client: {}", e.getMessage());
            }
        }
    }

    /**
     * Rewrites remote candidates and connection IP in the server's SDP answer
     * to point to the local relay socket on 127.0.0.1.
     */
    public String rewriteSdpAnswer(String sdp) {
        if (sdp == null) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = sdp.split("\r?\n");
        for (String line : lines) {
            if (line.startsWith("a=candidate:")) {
                line = rewriteCandidateLine(line);
            } else if (line.startsWith("c=IN IP4 ")) {
                line = "c=IN IP4 127.0.0.1";
            }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Rewrites a candidate line (e.g. from an SDP answer or trickle candidate)
     * so that libdatachannel communicates with this local relay on 127.0.0.1.
     */
    public String rewriteCandidateLine(String line) {
        String[] tokens = line.split(" ");
        if (tokens.length >= 6) {
            String remoteIp = tokens[4];
            try {
                int remotePort = Integer.parseInt(tokens[5]);
                InetAddress addr = InetAddress.getByName(remoteIp);
                this.targetServerAddress = new InetSocketAddress(addr, remotePort);
                log.info("SOCKS5 UDP Relay target set to remote candidate: {}:{}", remoteIp, remotePort);
            } catch (Exception e) {
                log.warn("Failed to parse candidate endpoint '{}:{}': {}", remoteIp, tokens[5], e.getMessage());
            }
            tokens[4] = "127.0.0.1";
            tokens[5] = String.valueOf(this.localPort);
            return String.join(" ", tokens);
        }
        return line;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                localSocket.close();
            } catch (Exception ignored) {}
            try {
                proxySocket.close();
            } catch (Exception ignored) {}
            try {
                tcpControlSocket.close();
            } catch (Exception ignored) {}
            log.info("Closed NetherNet SOCKS5 UDP relay. Bandwidth: Sent: {} KB, Received: {} KB (Total: {} KB)",
                    String.format("%.1f", totalBytesSent.get() / 1024.0),
                    String.format("%.1f", totalBytesReceived.get() / 1024.0),
                    String.format("%.1f", (totalBytesSent.get() + totalBytesReceived.get()) / 1024.0));
        }
    }
}
