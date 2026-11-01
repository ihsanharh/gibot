package com.ihsanharh.gibot.nethernet.peer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import com.ihsanharh.gibot.nethernet.codec.NetherNetCompressionDecoder;
import com.ihsanharh.gibot.nethernet.codec.NetherNetCompressionEncoder;
import com.ihsanharh.gibot.nethernet.codec.NetherNetEncryptionDecoder;
import com.ihsanharh.gibot.nethernet.codec.NetherNetEncryptionEncoder;
import com.ihsanharh.gibot.nethernet.codec.NetherNetPacketDecoder;
import com.ihsanharh.gibot.nethernet.codec.NetherNetPacketEncoder;
import com.ihsanharh.gibot.nethernet.initializer.NetherNetBedrockChannelInitializer;

import javax.crypto.SecretKey;
import java.util.Objects;

public class NetherNetBedrockPeer extends BedrockPeer {
    public NetherNetBedrockPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public void enableEncryption(@NonNull SecretKey secretKey) {
        Objects.requireNonNull(secretKey, "secretKey");
        if (!secretKey.getAlgorithm().equals("AES")) {
            throw new IllegalArgumentException("Invalid key algorithm");
        }

        ChannelPipeline pipeline = this.channel.pipeline();

        if (pipeline.get(NetherNetEncryptionEncoder.NAME) != null) {
            throw new IllegalStateException("Outbound encryption is already enabled");
        }
        if (pipeline.get(NetherNetEncryptionDecoder.NAME) != null) {
            throw new IllegalStateException("Inbound encryption is already enabled");
        }

        boolean useCtr = this.getCodec().getProtocolVersion() >= 428;

        if (pipeline.get(NetherNetCompressionDecoder.NAME) != null) {
            pipeline.addBefore(NetherNetCompressionDecoder.NAME, NetherNetEncryptionDecoder.NAME,
                    new NetherNetEncryptionDecoder(secretKey, EncryptionUtils.createCipher(useCtr, false, secretKey)));
        } else {
            pipeline.addBefore(NetherNetPacketDecoder.NAME, NetherNetEncryptionDecoder.NAME,
                    new NetherNetEncryptionDecoder(secretKey, EncryptionUtils.createCipher(useCtr, false, secretKey)));
        }

        if (pipeline.get(NetherNetCompressionEncoder.NAME) != null) {
            pipeline.addAfter(NetherNetCompressionEncoder.NAME, NetherNetEncryptionEncoder.NAME,
                    new NetherNetEncryptionEncoder(secretKey, EncryptionUtils.createCipher(useCtr, true, secretKey)));
        } else {
            pipeline.addBefore(NetherNetPacketEncoder.NAME, NetherNetEncryptionEncoder.NAME,
                    new NetherNetEncryptionEncoder(secretKey, EncryptionUtils.createCipher(useCtr, true, secretKey)));
        }
    }

    @Override
    public void setCompression(PacketCompressionAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        if (algorithm == PacketCompressionAlgorithm.NONE) {
            return;
        }
        this.setCompression(NetherNetBedrockChannelInitializer.getCompression());
    }

    @Override
    public void setCompression(CompressionStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");

        boolean prefixed = this.getCodec().getProtocolVersion() >= 649;

        ChannelPipeline pipeline = this.channel.pipeline();

        if (pipeline.get(NetherNetCompressionDecoder.NAME) == null) {
            pipeline.addBefore(NetherNetPacketDecoder.NAME, NetherNetCompressionDecoder.NAME,
                    new NetherNetCompressionDecoder(strategy, prefixed));
        }
        if (pipeline.get(NetherNetCompressionEncoder.NAME) == null) {
            pipeline.addBefore(NetherNetPacketEncoder.NAME, NetherNetCompressionEncoder.NAME,
                    new NetherNetCompressionEncoder(strategy, prefixed, 1));
        }
    }
}
