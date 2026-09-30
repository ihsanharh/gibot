package com.ihsanharh.gibot;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.log4j.Log4j2;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.proxy.ProxyHandler;
import net.lenni0451.commons.httpclient.proxy.ProxyType;
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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
        TOKENS,
        GIFT
    }

    public static void configureHttpProxy(HttpClient httpClient, String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return;
        }
        try {
            String normalized = proxyUrl.trim();
            if (!normalized.contains("://")) {
                normalized = "http://" + normalized;
            }
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http";
            ProxyType proxyType = scheme.startsWith("socks") ? ProxyType.SOCKS5 : ProxyType.HTTP;
            if (scheme.equalsIgnoreCase("socks4")) {
                proxyType = ProxyType.SOCKS4;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Proxy host cannot be empty in " + proxyUrl);
            }
            int port = uri.getPort();
            if (port == -1) {
                port = proxyType == ProxyType.HTTP ? 8080 : 1080;
            }

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

            if (proxyType == ProxyType.HTTP) {
                ProxyHandler proxyHandler = new ProxyHandler(proxyType, host, port, username, password);
                httpClient.setProxyHandler(proxyHandler);
                String maskedAuth = username != null ? (username + (password != null ? ":***@" : "@")) : "";
                log.info("Configured HTTP proxy for Microsoft Auth: {}://{}{}:{}", scheme, maskedAuth, host, port);
            } else {
                log.info("SOCKS proxy detected: SOCKS5 is used for Bedrock RakNet UDP session (Microsoft Auth connects direct via HTTPS)");
            }
        } catch (Exception e) {
            log.error("Failed to parse proxy URL '{}': {}", proxyUrl, e.getMessage());
            throw new IllegalArgumentException("Invalid proxy URL: " + proxyUrl, e);
        }
    }

    public static void main(String[] args) {
        boolean verbose = false;
        boolean jsonOutput = false;
        String proxyArg = null;
        String categoryArg = null;
        Integer maxTokensArg = null;
        java.util.List<String> cleanArgsList = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("-v") || arg.equalsIgnoreCase("--verbose")) {
                verbose = true;
            } else if (arg.equalsIgnoreCase("-j") || arg.equalsIgnoreCase("--json")) {
                jsonOutput = true;
            } else if ((arg.equalsIgnoreCase("-p") || arg.equalsIgnoreCase("--proxy")) && i + 1 < args.length) {
                proxyArg = args[++i];
            } else if (arg.toLowerCase().startsWith("--proxy=")) {
                proxyArg = arg.substring("--proxy=".length());
            } else if ((arg.equalsIgnoreCase("-c") || arg.equalsIgnoreCase("--category")) && i + 1 < args.length) {
                categoryArg = args[++i];
            } else if (arg.toLowerCase().startsWith("--category=")) {
                categoryArg = arg.substring("--category=".length());
            } else if (arg.toLowerCase().startsWith("-c=")) {
                categoryArg = arg.substring("-c=".length());
            } else if ((arg.equalsIgnoreCase("-m") || arg.equalsIgnoreCase("--max-tokens")) && i + 1 < args.length) {
                try {
                    maxTokensArg = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {}
            } else if (arg.toLowerCase().startsWith("--max-tokens=")) {
                try {
                    maxTokensArg = Integer.parseInt(arg.substring("--max-tokens=".length()));
                } catch (NumberFormatException ignored) {}
            } else if (arg.toLowerCase().startsWith("-m=")) {
                try {
                    maxTokensArg = Integer.parseInt(arg.substring("-m=".length()));
                } catch (NumberFormatException ignored) {}
            } else {
                cleanArgsList.add(arg);
            }
        }
        String[] cleanArgs = cleanArgsList.toArray(new String[0]);

        if (!verbose) {
            org.apache.logging.log4j.core.config.Configurator.setRootLevel(org.apache.logging.log4j.Level.WARN);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("com.ihsanharh.gibot", org.apache.logging.log4j.Level.INFO);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("org.cloudburstmc", org.apache.logging.log4j.Level.ERROR);
            org.apache.logging.log4j.core.config.Configurator.setAllLevels("io.netty", org.apache.logging.log4j.Level.ERROR);
        }

        if (cleanArgs.length == 0 || cleanArgs[0].equals("--help") || cleanArgs[0].equals("-h")) {
            System.out.println("Usage:");
            System.out.println("  ./gibot tokens                                 -> Quick fetch token balances (general & costume)");
            System.out.println("  ./gibot fetch                                  -> Fetch store items & token balance");
            System.out.println("  ./gibot fetch [item]                           -> Fetch specific item token cost & image");
            System.out.println("  ./gibot gift [username] [item]                 -> Gift an item to a player");
            System.out.println("  ./gibot gift [username] [item] -c [category]   -> Gift an item directly inside a category");
            System.out.println("Options:");
            System.out.println("  -c, --category <name>          -> Target subcategory (e.g. 'Regular Costume', 'Hats')");
            System.out.println("  -p, --proxy <url>              -> HTTP or SOCKS5 proxy URL");
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
                    System.out.println("{\"status\":\"error\",\"message\":\"Missing arguments for gift! Usage: gift [username] [item] [-c category]\"}");
                } else {
                    System.out.println("Failed: Missing arguments for gift! Usage: ./gibot gift [username] [item] [-c category]");
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
        } else if (cleanArgs[0].equalsIgnoreCase("tokens") || cleanArgs[0].equalsIgnoreCase("balance")) {
            mode = BotMode.TOKENS;
        } else if (cleanArgs[0].equalsIgnoreCase("fetch")) {
            if (cleanArgs.length > 1 && (cleanArgs[1].equalsIgnoreCase("--tokens-only") || cleanArgs[1].equalsIgnoreCase("-t"))) {
                mode = BotMode.TOKENS;
            } else {
                mode = BotMode.FETCH;
                if (cleanArgs.length > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < cleanArgs.length; i++) {
                        if (i > 1) sb.append(" ");
                        sb.append(cleanArgs[i]);
                    }
                    targetItem = sb.toString().trim();
                }
            }
        } else {
            if (jsonOutput) {
                System.out.println("{\"status\":\"error\",\"message\":\"Unknown command '" + cleanArgs[0] + "'. Usage: tokens, fetch, gift [username] [item] [-c category]\"}");
            } else {
                System.out.println("Failed: Unknown command '" + cleanArgs[0] + "'. Usage: tokens, fetch, gift [username] [item] [-c category]");
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
            String catInfo = categoryArg != null && !categoryArg.isBlank() ? " (Category: \"" + categoryArg + "\")" : "";
            log.info("Action: Gift \"{}\"{} -> Player \"{}\"", targetItem, catInfo, recipient);
        } else if (targetItem != null && !targetItem.isBlank()) {
            log.info("Target Item: \"{}\"", targetItem);
        }

        final BotMode finalMode = mode;
        final String finalRecipient = recipient;
        final String finalTargetItem = targetItem;
        final String finalTargetCategory = categoryArg != null && !categoryArg.isBlank() ? categoryArg.trim() : null;
        final boolean finalJsonOutput = jsonOutput;
        final Integer finalMaxTokens = maxTokensArg;

        Socks5UdpRelay socks5Relay = null;
        try {
            HttpClient httpClient = MinecraftAuth.createHttpClient();
            if (proxyArg != null && !proxyArg.isBlank()) {
                configureHttpProxy(httpClient, proxyArg);
                String normalized = proxyArg.trim().toLowerCase();
                if (normalized.startsWith("socks5://") || normalized.startsWith("socks://") || (!normalized.contains("://") && !normalized.startsWith("http"))) {
                    socks5Relay = Socks5UdpRelay.create(proxyArg);
                }
            }
            Account account = Account.getOrAuthenticate(httpClient, CODEC.getMinecraftVersion());

            InetSocketAddress targetAddress = new InetSocketAddress(host, port);
            NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();

            final Socks5UdpRelay finalSocks5Relay = socks5Relay;
            Bootstrap bootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class, datagramChannel -> {
                        if (finalSocks5Relay != null) {
                            datagramChannel.pipeline().addFirst("socks5-udp",
                                    new Socks5UdpHandler(finalSocks5Relay.getRelayAddress(), targetAddress));
                            log.info("Attached SOCKS5 UDP handler to datagram pipeline.");
                        }
                    }))
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, CODEC.getRaknetProtocolVersion())
                    .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                    .option(RakChannelOption.RAK_IP_DONT_FRAGMENT, finalSocks5Relay == null)
                    .option(RakChannelOption.RAK_MTU_SIZES, finalSocks5Relay != null ? new Integer[]{1400, 1200, 576} : new Integer[]{1492, 1200, 576})
                    .option(RakChannelOption.RAK_CLIENT_INTERNAL_ADDRESSES, 20)
                    .option(RakChannelOption.RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS, 500)
                    .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
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
                                    finalMode, finalRecipient, finalTargetItem, finalTargetCategory, finalJsonOutput,
                                    finalMaxTokens
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

            ScheduledExecutorService processWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GiBot-ProcessWatchdog");
                t.setDaemon(true);
                return t;
            });
            processWatchdog.schedule(() -> {
                log.error("Process execution deadline reached (185s limit). Forcing shutdown.");
                if (finalJsonOutput) {
                    System.out.println("{\"status\":\"error\",\"message\":\"GiBot process reached maximum 3 minute execution limit\"}");
                } else {
                    System.out.println("Failed: GiBot process reached maximum 3 minute execution limit");
                }
                System.exit(1);
            }, 185, TimeUnit.SECONDS);

            final Socks5UdpRelay relayToClose = socks5Relay;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    processWatchdog.shutdownNow();
                    if (relayToClose != null) {
                        relayToClose.close();
                    }
                    channel.close().sync();
                    eventLoopGroup.shutdownGracefully().sync();
                } catch (Exception ignored) {
                }
            }));

            // Block and wait until the task is complete and session disconnects
            channel.closeFuture().sync();
            processWatchdog.shutdownNow();
            log.info("Session closed. GiBot job complete.");
            eventLoopGroup.shutdownGracefully().sync();
        } catch (Exception e) {
            if (finalJsonOutput) {
                System.out.println("{\"status\":\"error\",\"message\":\"" + (e.getMessage() != null ? e.getMessage() : "Fatal connection error") + "\"}");
            } else {
                System.out.println("Failed: " + (e.getMessage() != null ? e.getMessage() : "Fatal connection error"));
            }
            log.error("Fatal error in GiBot", e);
            System.exit(1);
        } finally {
            if (socks5Relay != null) {
                socks5Relay.close();
            }
        }
    }
}
