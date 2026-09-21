package com.ihsanharh.gibot;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.log4j.Log4j2;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v776.serializer.ItemComponentSerializer_v776;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;

@Log4j2
public class GiBot {
    /**
     * Custom serializer for ItemComponentPacket supporting up to 10,000 item definitions in Bedrock 1.26.
     */
    public static class FixedItemComponentSerializer implements BedrockPacketSerializer<ItemComponentPacket> {
        public static final FixedItemComponentSerializer INSTANCE = new FixedItemComponentSerializer();

        @Override
        public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ItemComponentPacket packet) {
            ItemComponentSerializer_v776.INSTANCE.serialize(buffer, helper, packet);
        }

        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ItemComponentPacket packet) {
            helper.readArray(buffer, packet.getItems(), (buf, packetHelper) -> {
                String name = packetHelper.readString(buf);
                short itemId = buf.readShortLE();
                boolean componentBased = buf.readBoolean();
                int version = VarInts.readInt(buf);
                NbtMap data = packetHelper.readTag(buf, NbtMap.class);
                return new SimpleItemDefinition(name, itemId, ItemVersion.from(version), componentBased, data);
            }, 10000);
        }
    }

    public static final BedrockCodecHelper HELPER = Bedrock_v2193.CODEC.createHelper();
    public static final BedrockCodec CODEC = Bedrock_v2193.CODEC
            .toBuilder()
            .protocolVersion(2193)
            .minecraftVersion("1.26.51")
            .updateSerializer(ItemComponentPacket.class, FixedItemComponentSerializer.INSTANCE)
            .helper(() -> HELPER)
            .build();

    public enum BotMode {
        FETCH,
        GIFT
    }

    public static void main(String[] args) {
        boolean verbose = false;
        boolean jsonOutput = false;
        java.util.List<String> cleanArgsList = new java.util.ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("-v") || arg.equalsIgnoreCase("--verbose")) {
                verbose = true;
            } else if (arg.equalsIgnoreCase("-j") || arg.equalsIgnoreCase("--json")) {
                jsonOutput = true;
            } else {
                cleanArgsList.add(arg);
            }
        }
        String[] cleanArgs = cleanArgsList.toArray(new String[0]);

        if (!verbose) {
            org.apache.logging.log4j.core.config.Configurator.setRootLevel(org.apache.logging.log4j.Level.OFF);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("com.ihsanharh.gibot", org.apache.logging.log4j.Level.OFF);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("org.cloudburstmc", org.apache.logging.log4j.Level.OFF);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("io.netty", org.apache.logging.log4j.Level.OFF);
        }

        if (cleanArgs.length == 0 || cleanArgs[0].equals("--help") || cleanArgs[0].equals("-h")) {
            System.out.println("Usage:");
            System.out.println("  ./gibot fetch                  -> Fetch store items & token balance");
            System.out.println("  ./gibot fetch [item]           -> Fetch specific item token cost & image");
            System.out.println("  ./gibot gift [username] [item] -> Gift an item to a player");
            System.out.println("Options:");
            System.out.println("  -j, --json                     -> Output in machine-readable JSON format");
            System.out.println("  -v, --verbose                  -> Show full connection & debug logs");
            return;
        }

        BotMode mode;
        String recipient = null;
        String targetItem = null;

        if (cleanArgs[0].equalsIgnoreCase("gift")) {
            mode = BotMode.GIFT;
            if (cleanArgs.length < 3) {
                if (jsonOutput) {
                    System.out.println("{\"status\":\"error\",\"error\":\"Missing arguments for gift! Usage: gift [username] [item]\"}");
                } else {
                    System.out.println("Failed: Missing arguments for gift! Usage: ./gibot gift [username] [item]");
                }
                System.exit(1);
                return;
            }
            recipient = cleanArgs[1];
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < cleanArgs.length; i++) {
                if (i > 2) sb.append(" ");
                sb.append(cleanArgs[i]);
            }
            targetItem = sb.toString().trim();
        } else if (cleanArgs[0].equalsIgnoreCase("fetch")) {
            mode = BotMode.FETCH;
            if (cleanArgs.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < cleanArgs.length; i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(cleanArgs[i]);
                }
                targetItem = sb.toString().trim();
            }
        } else {
            if (jsonOutput) {
                System.out.println("{\"status\":\"error\",\"error\":\"Unknown command '" + cleanArgs[0] + "'. Usage: fetch, fetch [item], gift [username] [item]\"}");
            } else {
                System.out.println("Failed: Unknown command '" + cleanArgs[0] + "'. Usage: fetch, fetch [item], gift [username] [item]");
            }
            System.exit(1);
            return;
        }

        String host = "geo.hivebedrock.network";
        int port = 19132;

        CatalogManager catalogManager = new CatalogManager();
        catalogManager.setJsonOutput(jsonOutput);

        log.info("Connecting to {}:{} (Minecraft {})", host, port, CODEC.getMinecraftVersion());
        log.info("Mode: {}", mode);
        if (mode == BotMode.GIFT) {
            log.info("Action: Gift \"{}\" -> Player \"{}\"", targetItem, recipient);
        } else if (targetItem != null && !targetItem.isBlank()) {
            log.info("Target Item: \"{}\"", targetItem);
        }

        final BotMode finalMode = mode;
        final String finalRecipient = recipient;
        final String finalTargetItem = targetItem;
        final boolean finalJsonOutput = jsonOutput;

        try {
            HttpClient httpClient = MinecraftAuth.createHttpClient();
            Account account = Account.getOrAuthenticate(httpClient, CODEC.getMinecraftVersion());

            InetSocketAddress targetAddress = new InetSocketAddress(host, port);
            NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();

            Bootstrap bootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, CODEC.getRaknetProtocolVersion())
                    .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                    .option(RakChannelOption.RAK_IP_DONT_FRAGMENT, true)
                    .option(RakChannelOption.RAK_MTU_SIZES, new Integer[]{1492, 1200, 576})
                    .option(RakChannelOption.RAK_CLIENT_INTERNAL_ADDRESSES, 20)
                    .option(RakChannelOption.RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS, 500)
                    .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                    .handler(new BedrockChannelInitializer<BedrockClientSession>() {
                        @Override
                        protected BedrockClientSession createSession0(BedrockPeer peer, int subClientId) {
                            return new BedrockClientSession(peer, subClientId);
                        }

                        @Override
                        protected void initSession(BedrockClientSession session) {
                            session.setCodec(CODEC);
                            BotPacketHandler handler = new BotPacketHandler(
                                    session, account, targetAddress, catalogManager,
                                    finalMode, finalRecipient, finalTargetItem, finalJsonOutput
                            );
                            session.setPacketHandler(handler);

                            // Silently catch and suppress non-fatal pipeline decoding exceptions (e.g. unmapped cosmetics)
                            session.getPeer().getChannel().pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                }
                            });

                            RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
                            packet.setProtocolVersion(CODEC.getProtocolVersion());
                            session.sendPacketImmediately(packet);
                            log.info("Initiated session. Sent RequestNetworkSettingsPacket.");
                        }
                    });

            log.info("Connecting via RakNet...");
            ChannelFuture future = bootstrap.connect(targetAddress).sync();
            Channel channel = future.channel();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    channel.close().sync();
                    eventLoopGroup.shutdownGracefully().sync();
                } catch (Exception ignored) {
                }
            }));

            // Block and wait until the task is complete and session disconnects
            channel.closeFuture().sync();
            log.info("Session closed. GiBot job complete.");
            eventLoopGroup.shutdownGracefully().sync();
        } catch (Exception e) {
            if (finalJsonOutput) {
                System.out.println("{\"status\":\"error\",\"error\":\"" + (e.getMessage() != null ? e.getMessage() : "Fatal connection error") + "\"}");
            } else {
                System.out.println("Failed: " + (e.getMessage() != null ? e.getMessage() : "Fatal connection error"));
            }
            log.error("Fatal error in GiBot", e);
            System.exit(1);
        }
    }
}
