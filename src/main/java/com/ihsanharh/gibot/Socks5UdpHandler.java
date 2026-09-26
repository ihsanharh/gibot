package com.ihsanharh.gibot;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.log4j.Log4j2;

import java.net.InetSocketAddress;

@Log4j2
public class Socks5UdpHandler extends ChannelDuplexHandler {
    private final InetSocketAddress relayAddress;
    private final InetSocketAddress targetServerAddress;
    private final byte[] targetIpBytes;
    private final int targetPort;
    private final java.util.concurrent.atomic.AtomicLong totalBytesSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalBytesReceived = new java.util.concurrent.atomic.AtomicLong();

    public Socks5UdpHandler(InetSocketAddress relayAddress, InetSocketAddress targetServerAddress) {
        this.relayAddress = relayAddress;
        this.targetServerAddress = targetServerAddress;
        this.targetIpBytes = targetServerAddress.getAddress().getAddress();
        this.targetPort = targetServerAddress.getPort();
    }

    @Override
    public void connect(ChannelHandlerContext ctx, java.net.SocketAddress remoteAddress, java.net.SocketAddress localAddress, ChannelPromise promise) throws Exception {
        log.info("Redirecting UDP socket connect from {} to SOCKS5 relay {}", remoteAddress, relayAddress);
        ChannelPipeline p = ctx.pipeline();
        String routeHandlerName = "rak-client-proxy-route-handler";
        if (p.get(routeHandlerName) != null) {
            p.remove(routeHandlerName);
            log.info("Removed default {} to handle SOCKS5 UDP encapsulation directly.", routeHandlerName);
        }
        promise.addListener(f -> {
            if (f.isSuccess()) {
                log.info("UDP socket successfully connected to SOCKS5 relay!");
            } else {
                log.error("UDP socket connect to SOCKS5 relay failed: ", f.cause());
            }
        });
        ctx.connect(relayAddress, localAddress, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket) && !(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }

        ByteBuf content;
        if (msg instanceof DatagramPacket) {
            content = ((DatagramPacket) msg).content();
        } else {
            content = (ByteBuf) msg;
        }

        try {
            // SOCKS5 UDP Request Header (RFC 1928 Section 7):
            // +----+------+------+----------+----------+----------+
            // |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
            // +----+------+------+----------+----------+----------+
            // | 2  |  1   |  1   | Variable |    2     | Variable |
            // +----+------+------+----------+----------+----------+
            int readable = content.readableBytes();
            ByteBuf outBuf = ctx.alloc().buffer(10 + readable);
            outBuf.writeShort(0); // RSV = 0x0000
            outBuf.writeByte(0);  // FRAG = 0x00 (standalone datagram)
            outBuf.writeByte(1);  // ATYP = 0x01 (IPv4)
            outBuf.writeBytes(targetIpBytes);
            outBuf.writeShort(targetPort);
            outBuf.writeBytes(content);

            totalBytesSent.addAndGet(outBuf.readableBytes());
            DatagramPacket outPacket = new DatagramPacket(outBuf, relayAddress);
            ctx.write(outPacket, promise);
        } finally {
            io.netty.util.ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        totalBytesReceived.addAndGet(buf.readableBytes());

        if (buf.readableBytes() < 10) {
            log.warn("Socks5UdpHandler: Received UDP packet too short: {} bytes", buf.readableBytes());
            ctx.fireChannelRead(msg);
            return;
        }

        boolean release = true;
        try {
            buf.skipBytes(2); // Skip RSV
            byte frag = buf.readByte();   // FRAG
            byte atyp = buf.readByte();

            if (atyp == 1) { // IPv4: 4 bytes IP + 2 bytes port
                buf.skipBytes(6);
            } else if (atyp == 3) { // Domain: 1 byte len + domain + 2 bytes port
                int len = buf.readUnsignedByte();
                buf.skipBytes(len + 2);
            } else if (atyp == 4) { // IPv6: 16 bytes IP + 2 bytes port
                buf.skipBytes(18);
            } else {
                log.warn("Socks5UdpHandler: Unknown ATYP {} in SOCKS5 UDP response", atyp);
                release = false;
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf payload = buf.retainedSlice();
            log.debug("Socks5UdpHandler <- Proxy: Received {} bytes RakNet payload", payload.readableBytes());
            ctx.fireChannelRead(payload);
        } finally {
            if (release) {
                packet.release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        long sent = totalBytesSent.get();
        long recv = totalBytesReceived.get();
        long total = sent + recv;
        log.info("Proxy session bandwidth: Sent: {} KB, Received: {} KB (Total: {} KB)",
                String.format("%.1f", sent / 1024.0),
                String.format("%.1f", recv / 1024.0),
                String.format("%.1f", total / 1024.0));
        super.channelInactive(ctx);
    }
}
