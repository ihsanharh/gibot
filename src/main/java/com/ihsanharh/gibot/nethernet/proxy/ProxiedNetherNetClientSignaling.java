package com.ihsanharh.gibot.nethernet.proxy;

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator around NetherNetClientSignaling that intercepts server SDP answers
 * and trickle candidates to rewrite remote endpoints to the local SOCKS5 UDP relay.
 */
@Log4j2
public class ProxiedNetherNetClientSignaling implements NetherNetClientSignaling {
    private final NetherNetClientSignaling delegate;
    private final NetherNetSocks5Relay relay;

    public ProxiedNetherNetClientSignaling(NetherNetClientSignaling delegate, NetherNetSocks5Relay relay) {
        this.delegate = delegate;
        this.relay = relay;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        return delegate.connect(remoteAddress);
    }

    @Override
    public void setFailureHandler(FailureHandler handler) {
        delegate.setFailureHandler(handler);
    }

    @Override
    public boolean usesTrickleIce() {
        return delegate.usesTrickleIce();
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        delegate.sendSignal(targetNetworkId, data);
    }

    @Override
    public void sendDescription(String targetNetworkId, String sdp) {
        log.info("Sending SDP offer to {}:\n{}", targetNetworkId, sdp);
        delegate.sendDescription(targetNetworkId, sdp);
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        delegate.setSignalHandler(connectionId, signal -> {
            NetherNetConstants.Signal parsed = NetherNetConstants.parseSignal(signal);
            if (parsed != null) {
                if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE.equals(parsed.type())) {
                    String originalSdp = parsed.payload();
                    log.info("Received server SDP answer:\n{}", originalSdp);
                    String rewrittenSdp = relay.rewriteSdpAnswer(originalSdp);
                    log.info("Rewritten SDP answer:\n{}", rewrittenSdp);
                    String rewrittenSignal = NetherNetConstants.buildSignalConnectResponse(parsed.connectionId(), rewrittenSdp);
                    handler.onSignal(rewrittenSignal);
                    return;
                } else if (NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD.equals(parsed.type())) {
                    String originalCandidate = parsed.payload();
                    String rewrittenCandidate = relay.rewriteCandidateLine(originalCandidate);
                    String rewrittenSignal = NetherNetConstants.buildSignalCandidateAdd(parsed.connectionId(), rewrittenCandidate);
                    handler.onSignal(rewrittenSignal);
                    return;
                }
            }
            handler.onSignal(signal);
        });
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        delegate.removeSignalHandler(connectionId);
    }

    @Override
    public String getLocalNetworkId() {
        return delegate.getLocalNetworkId();
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            relay.close();
        }
    }
}
