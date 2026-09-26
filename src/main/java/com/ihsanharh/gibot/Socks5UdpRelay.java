package com.ihsanharh.gibot;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Log4j2
@Getter
public class Socks5UdpRelay implements Closeable {
    private final Socket tcpSocket;
    private final InetSocketAddress relayAddress;

    private Socks5UdpRelay(Socket tcpSocket, InetSocketAddress relayAddress) {
        this.tcpSocket = tcpSocket;
        this.relayAddress = relayAddress;
    }

    public static Socks5UdpRelay create(String proxyUrl) throws IOException {
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

        log.info("Connecting to SOCKS5 proxy control at {}:{}...", host, port);
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
            throw new IOException("Invalid SOCKS5 greeting response from proxy");
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
        log.info("SOCKS5 UDP Relay successfully established at {}", relay);

        // Keep the TCP socket alive indefinitely as the session anchor
        socket.setSoTimeout(0);
        return new Socks5UdpRelay(socket, relay);
    }

    @Override
    public void close() {
        try {
            tcpSocket.close();
            log.info("Closed SOCKS5 TCP control socket.");
        } catch (Exception ignored) {
        }
    }
}
