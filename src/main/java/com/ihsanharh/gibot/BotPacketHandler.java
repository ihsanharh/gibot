package com.ihsanharh.gibot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.ToastRequestPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import javax.crypto.SecretKey;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Bedrock network handshake, spawns into the server,
 * executes /gift, and captures the form response.
 */
@Log4j2
public class BotPacketHandler implements BedrockPacketHandler {
    private final BedrockClientSession session;
    private final Account account;
    private final SocketAddress serverAddress;
    private final CatalogManager catalogManager;
    private final GiBot.BotMode mode;
    private final String recipient;
    private String targetItem;
    private final String targetCategory;

    private long runtimeEntityId;
    private Vector3f currentPosition = Vector3f.ZERO;
    private float yaw = 0f;
    private float pitch = 0f;
    private long tick = 0;

    private volatile boolean pendingTeleport = false;

    public enum GiftStep {
        CONNECTING,
        SPAWNING,
        IDLE,
        SEARCHING_SUBMENUS,
        SELECTING_ITEM,
        SELECTING_GIFT_METHOD,
        ENTERING_USERNAME,
        AWAITING_CONFIRMATION_MODAL,
        CONFIRMING_GIFT,
        AWAITING_COMPLETION,
        FINISHED
    }

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean spawned = new AtomicBoolean(false);
    private final boolean jsonOutput;
    private final AtomicBoolean outputPrinted = new AtomicBoolean(false);

    private final long startTimeMs;
    private volatile long lastActionTimeMs;
    private volatile String lastActionDescription;
    private volatile int lastFormId = -1;
    private volatile String lastFormTitle = null;
    private volatile String lastFormType = null;
    private volatile String lastServerMessage = null;
    private ScheduledFuture<?> watchdogTask = null;

    private GiftStep giftStep = GiftStep.CONNECTING;
    private ScheduledFuture<?> usernameTimeoutTask = null;
    private ScheduledFuture<?> giftRetryTask = null;
    private int giftCommandAttempts = 0;
    private final Queue<CatalogManager.SubcategoryButton> giftSearchQueue = new LinkedList<>();
    private final Queue<CatalogManager.SubcategoryButton> crawlQueue = new LinkedList<>();
    private CatalogManager.SubcategoryButton currentCrawlingSubcategory = null;
    private boolean crawlInProgress = false;
    private int totalSubcategoriesToCrawl = 0;
    private int completedSubcategories = 0;
    private String activeSubcategory = null;
    private int cachedTokenBalance = -1;
    private final java.util.Map<String, Integer> cachedCategoryTokens = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private boolean searchedInCurrentSubcategory = false;
    private int targetItemCost = 0;
    private final AtomicBoolean exiting = new AtomicBoolean(false);

    public BotPacketHandler(BedrockClientSession session, Account account, SocketAddress serverAddress, CatalogManager catalogManager, GiBot.BotMode mode, String recipient, String targetItem, boolean jsonOutput) {
        this(session, account, serverAddress, catalogManager, mode, recipient, targetItem, null, jsonOutput);
    }

    public BotPacketHandler(BedrockClientSession session, Account account, SocketAddress serverAddress, CatalogManager catalogManager, GiBot.BotMode mode, String recipient, String targetItem, String targetCategory, boolean jsonOutput) {
        this.session = session;
        this.account = account;
        this.serverAddress = serverAddress;
        this.catalogManager = catalogManager;
        this.mode = mode;
        this.recipient = recipient;
        this.targetItem = targetItem;
        this.targetCategory = targetCategory != null && !targetCategory.isBlank() ? targetCategory.trim() : null;
        this.jsonOutput = jsonOutput;
        this.startTimeMs = System.currentTimeMillis();
        this.lastActionTimeMs = this.startTimeMs;
        this.lastActionDescription = "Session initialized, awaiting network settings";
        this.giftStep = GiftStep.CONNECTING;

        // Safety watchdog: auto-terminate after 3 minutes (180s) max if not already finished.
        // Started right at session initialization to cover slow proxies, handshakes, or in-game stalls.
        this.watchdogTask = ticker.schedule(() -> {
            if (!exiting.get()) {
                log.warn("Bot execution watchdog reached (3 minutes / 180s limit). Disconnecting... Last state: {}", getLastKnownStateSummary());
                outputFailure(String.format("Gifting timed out: Watchdog limit (3 minutes) reached. Last state: %s",
                        getLastKnownStateSummary()));
                disconnectAndExit(1);
            }
        }, 180, TimeUnit.SECONDS);
    }

    public void updateState(String action) {
        this.lastActionDescription = action;
        this.lastActionTimeMs = System.currentTimeMillis();
        log.debug("State update: step={}, action='{}'", giftStep, action);
    }

    public void updateState(GiftStep step, String action) {
        this.giftStep = step;
        this.lastActionDescription = action;
        this.lastActionTimeMs = System.currentTimeMillis();
        log.debug("State update: step={}, action='{}'", step, action);
    }

    public JsonObject getLastKnownStateJson() {
        JsonObject state = new JsonObject();
        state.addProperty("step", giftStep != null ? giftStep.name() : "UNKNOWN");
        state.addProperty("lastAction", lastActionDescription != null ? lastActionDescription : "None");
        long now = System.currentTimeMillis();
        state.addProperty("elapsedSeconds", (now - startTimeMs) / 1000);
        state.addProperty("timeSinceLastActionSeconds", (now - lastActionTimeMs) / 1000);
        if (lastFormId != -1) {
            state.addProperty("lastFormId", lastFormId);
        }
        if (lastFormTitle != null) {
            state.addProperty("lastFormTitle", lastFormTitle);
        }
        if (lastFormType != null) {
            state.addProperty("lastFormType", lastFormType);
        }
        if (activeSubcategory != null) {
            state.addProperty("activeSubcategory", activeSubcategory);
        }
        state.addProperty("giftCommandAttempts", giftCommandAttempts);
        if (cachedTokenBalance != -1) {
            state.addProperty("availableTokens", cachedTokenBalance);
        }
        if (targetItemCost > 0) {
            state.addProperty("requiredTokens", targetItemCost);
        }
        if (lastServerMessage != null && !lastServerMessage.isBlank()) {
            state.addProperty("lastServerMessage", lastServerMessage);
        }
        if (recipient != null) {
            state.addProperty("recipient", recipient);
        }
        if (targetItem != null) {
            state.addProperty("targetItem", targetItem);
        }
        return state;
    }

    public String getLastKnownStateSummary() {
        long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
        long sinceAction = (System.currentTimeMillis() - lastActionTimeMs) / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("step=%s, lastAction='%s' (%ds ago, total elapsed %ds)",
                giftStep, lastActionDescription != null ? lastActionDescription : "None", sinceAction, elapsed));
        if (lastFormId != -1) {
            sb.append(String.format(", form=[id=%d, title='%s', type='%s']",
                    lastFormId, lastFormTitle != null ? lastFormTitle : "", lastFormType != null ? lastFormType : ""));
        }
        if (activeSubcategory != null) {
            sb.append(String.format(", subcategory='%s'", activeSubcategory));
        }
        sb.append(String.format(", /gift attempts=%d", giftCommandAttempts));
        if (cachedTokenBalance != -1) {
            sb.append(String.format(", tokens=%d", cachedTokenBalance));
        }
        if (lastServerMessage != null && !lastServerMessage.isBlank()) {
            sb.append(String.format(", lastServerMsg='%s'", lastServerMessage));
        }
        return sb.toString();
    }

    public void outputSuccess(String message) {
        if (!outputPrinted.compareAndSet(false, true)) return;
        this.giftStep = GiftStep.FINISHED;
        updateState(GiftStep.FINISHED, "Gifting completed successfully: " + message);

        boolean isCostume = (targetCategory != null && targetCategory.equalsIgnoreCase("Regular Costume"))
                || (targetItem != null && catalogManager.getData().getItems().containsKey(targetItem)
                && "Regular Costume".equalsIgnoreCase(catalogManager.getData().getItems().get(targetItem).getCategory()));

        int remainingTokens = -1;
        int remainingCostumeTokens = -1;

        if (isCostume) {
            Integer costumeAvail = cachedCategoryTokens.get("Regular Costume");
            if (costumeAvail != null && costumeAvail > 0) {
                remainingCostumeTokens = Math.max(0, costumeAvail - 1);
            }
        } else if (cachedTokenBalance != -1) {
            int cost = (targetItemCost > 0) ? targetItemCost : 1;
            remainingTokens = Math.max(0, cachedTokenBalance - cost);
        }

        if (jsonOutput) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "success");
            if (mode == GiBot.BotMode.GIFT) {
                obj.addProperty("recipient", recipient);
                obj.addProperty("item", targetItem);
                if (targetCategory != null) {
                    obj.addProperty("category", targetCategory);
                }
                if (remainingTokens != -1) {
                    obj.addProperty("remainingTokens", remainingTokens);
                    obj.addProperty("remainingBalance", remainingTokens);
                }
                if (remainingCostumeTokens != -1) {
                    obj.addProperty("remainingCostumeTokens", remainingCostumeTokens);
                }
            }
            obj.addProperty("message", message);
            obj.add("lastState", getLastKnownStateJson());
            System.out.println(obj.toString());
        } else {
            if (mode == GiBot.BotMode.GIFT) {
                if (remainingCostumeTokens != -1) {
                    System.out.println(String.format("Success: %s (Remaining Costume Tokens: %d)", message, remainingCostumeTokens));
                } else if (remainingTokens != -1) {
                    System.out.println(String.format("Success: %s (Remaining Tokens: %d)", message, remainingTokens));
                } else {
                    System.out.println("Success: " + message);
                }
            } else {
                System.out.println("Success: " + message);
            }
        }
    }

    public void outputFailure(String error) {
        if (!outputPrinted.compareAndSet(false, true)) return;
        updateState("Failure: " + error);
        if (jsonOutput) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "error");
            obj.addProperty("message", error);
            if (cachedTokenBalance != -1) {
                obj.addProperty("availableTokens", cachedTokenBalance);
            }
            if (targetItemCost > 0) {
                obj.addProperty("requiredTokens", targetItemCost);
            }
            obj.add("lastState", getLastKnownStateJson());
            System.out.println(obj.toString());
        } else {
            System.out.println(String.format("Failed: %s [Last state: %s]", error, getLastKnownStateSummary()));
        }
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        log.info("Server NetworkSettings received: compression algorithm={}, threshold={}", packet.getCompressionAlgorithm(), packet.getCompressionThreshold());

        if (packet.getCompressionThreshold() > 0) {
            session.setCompression(packet.getCompressionAlgorithm());
        } else {
            session.setCompression(PacketCompressionAlgorithm.NONE);
        }

        try {
            JSONObject skinData = loadSkinData();
            int protocolVersion = session.getCodec().getProtocolVersion();
            var authPayload = ForgeryUtils.forgeOnlineAuthData(account.getAuthManager());
            String skinJwt = ForgeryUtils.forgeOnlineSkinData(account, skinData, serverAddress);

            LoginPacket login = new LoginPacket();
            login.setProtocolVersion(protocolVersion);
            login.setAuthPayload(authPayload);
            login.setClientJwt(skinJwt);

            log.info("Sending LoginPacket with protocol version {}", protocolVersion);
            updateState("Received NetworkSettings, sent LoginPacket (protocol " + protocolVersion + ")");
            session.sendPacketImmediately(login);
        } catch (Exception e) {
            log.error("Failed to construct or send LoginPacket", e);
            session.disconnect("Login preparation failure");
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        log.info("Handling ServerToClientHandshakePacket: deriving ECDH shared secret...");
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getJwt());
            JSONObject saltJwt = new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload()));
            String x5u = jws.getHeader(HeaderParameterNames.X509_URL);
            ECPublicKey serverKey = EncryptionUtils.parseKey(x5u);
            byte[] salt = Base64.getDecoder().decode(JsonUtils.childAsType(saltJwt, "salt", String.class));

            SecretKey secretKey = EncryptionUtils.getSecretKey(
                    account.getAuthManager().getSessionKeyPair().getPrivate(),
                    serverKey,
                    salt
            );

            session.enableEncryption(secretKey);
            log.info("Session encryption successfully established.");
            updateState("Session encryption enabled, sent ClientToServerHandshake");
        } catch (Exception e) {
            log.error("Failed to enable encryption", e);
            session.disconnect("Encryption handshake failed");
            return PacketSignal.HANDLED;
        }

        ClientToServerHandshakePacket clientHandshake = new ClientToServerHandshakePacket();
        session.sendPacketImmediately(clientHandshake);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        log.info("Server PlayStatus: {}", packet.getStatus());

        if (packet.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS) {
            updateState("PlayStatus LOGIN_SUCCESS received, sent ClientCacheStatusPacket");
            ClientCacheStatusPacket cacheStatus = new ClientCacheStatusPacket();
            cacheStatus.setSupported(false);
            session.sendPacketImmediately(cacheStatus);
        } else if (packet.getStatus() == PlayStatusPacket.Status.PLAYER_SPAWN) {
            log.info("Bot spawned in world.");
            updateState(GiftStep.IDLE, "Spawned in world, initialized player, starting keepalive ticker");

            SetLocalPlayerAsInitializedPacket initializedPacket = new SetLocalPlayerAsInitializedPacket();
            initializedPacket.setRuntimeEntityId(this.runtimeEntityId);
            session.sendPacketImmediately(initializedPacket);

            ServerboundLoadingScreenPacket endLoading = new ServerboundLoadingScreenPacket();
            endLoading.setType(ServerboundLoadingScreenPacketType.END_LOADING_SCREEN);
            session.sendPacketImmediately(endLoading);

            startTicking();
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePacksInfoPacket packet) {
        updateState("ResourcePacksInfo received, sent HAVE_ALL_PACKS response");
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        session.sendPacketImmediately(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackStackPacket packet) {
        updateState("ResourcePackStack received, sent COMPLETED pack response");
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        session.sendPacketImmediately(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        this.runtimeEntityId = packet.getRuntimeEntityId();
        this.currentPosition = packet.getPlayerPosition();
        if (packet.getRotation() != null) {
            this.pitch = packet.getRotation().getX();
            this.yaw = packet.getRotation().getY();
        }

        if (packet.getItemDefinitions() != null && !packet.getItemDefinitions().isEmpty()) {
            SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = SimpleDefinitionRegistry
                    .<ItemDefinition>builder()
                    .addAll(packet.getItemDefinitions())
                    .build();
            session.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
        }

        log.info("Received StartGamePacket: entityId={}", runtimeEntityId);
        updateState(GiftStep.SPAWNING, "StartGame received (entityId=" + runtimeEntityId + "), loading chunks");

        RequestChunkRadiusPacket chunkRadius = new RequestChunkRadiusPacket();
        chunkRadius.setRadius(1);
        chunkRadius.setMaxRadius(1);
        session.sendPacketImmediately(chunkRadius);

        ServerboundLoadingScreenPacket startLoading = new ServerboundLoadingScreenPacket();
        startLoading.setType(ServerboundLoadingScreenPacketType.START_LOADING_SCREEN);
        session.sendPacketImmediately(startLoading);

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ItemComponentPacket packet) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> builder = SimpleDefinitionRegistry
                .<ItemDefinition>builder()
                .add(new SimpleItemDefinition("minecraft:empty", 0, false));

        for (var item : packet.getItems()) {
            builder.add(new SimpleItemDefinition(item.getIdentifier(), item.getRuntimeId(), false));
        }

        session.getPeer().getCodecHelper().setItemDefinitions(builder.build());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(MovePlayerPacket packet) {
        if (packet.getRuntimeEntityId() == this.runtimeEntityId) {
            this.currentPosition = packet.getPosition();
            if (packet.getRotation() != null) {
                this.pitch = packet.getRotation().getX();
                this.yaw = packet.getRotation().getY();
            }
            this.pendingTeleport = true;
        }
        return PacketSignal.HANDLED;
    }

    public void disconnectAndExit(int exitCode) {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            watchdogTask = null;
        }
        if (giftRetryTask != null) {
            giftRetryTask.cancel(false);
            giftRetryTask = null;
        }
        if (usernameTimeoutTask != null) {
            usernameTimeoutTask.cancel(false);
            usernameTimeoutTask = null;
        }
        log.info("Bot job finished. Disconnecting from server (exit code {})... Last state: {}", exitCode, getLastKnownStateSummary());
        ticker.schedule(() -> {
            try {
                if (session != null) {
                    session.disconnect("Job complete");
                }
            } catch (Exception ignored) {}
            ticker.schedule(() -> System.exit(exitCode), 400, TimeUnit.MILLISECONDS);
        }, 300, TimeUnit.MILLISECONDS);
    }

    @Override
    public PacketSignal handle(ModalFormRequestPacket packet) {
        if (giftRetryTask != null) {
            giftRetryTask.cancel(false);
            giftRetryTask = null;
        }
        String rawJson = packet.getFormData();
        this.lastFormId = packet.getFormId();
        log.info("RAW FORM RECEIVED [id={}]: {}", packet.getFormId(), rawJson);
        CatalogManager.ParsedFormInfo info = catalogManager.processForm(rawJson, account.getDisplayName(), activeSubcategory);
        if (info != null) {
            if (info.getTokenBalance() != -1) {
                this.cachedTokenBalance = info.getTokenBalance();
            }
            this.cachedCategoryTokens.putAll(info.getCategoryTokens());
            if (info.getTitle() != null && !info.getTitle().isBlank()) {
                this.lastFormTitle = info.getTitle();
            }
        }
        updateState("Received form '" + (lastFormTitle != null ? lastFormTitle : "unknown") + "' (id=" + packet.getFormId() + ")");

        if (mode == GiBot.BotMode.GIFT) {
            handleGiftFlow(packet, info);
            return PacketSignal.HANDLED;
        }

        // --- TOKENS MODE (Quick token check without crawling items) ---
        if (mode == GiBot.BotMode.TOKENS && info.isMainForm()) {
            int generalTokens = info.getTokenBalance() != -1 ? info.getTokenBalance() : catalogManager.getData().getAccountTokenBalance();
            if (generalTokens == -1) {
                generalTokens = 0;
            }

            Integer costumeTokens = info.getCategoryTokens().get("Regular Costume");
            if (costumeTokens == null) {
                costumeTokens = catalogManager.getData().getCategoryTokens().get("Regular Costume");
            }
            int costumeTokensVal = costumeTokens != null ? costumeTokens : 0;

            if (jsonOutput) {
                JsonObject root = new JsonObject();
                root.addProperty("status", "success");
                root.addProperty("tokens", generalTokens);
                root.addProperty("costumeTokens", costumeTokensVal);
                JsonObject catObj = new JsonObject();
                for (java.util.Map.Entry<String, Integer> e : info.getCategoryTokens().entrySet()) {
                    catObj.addProperty(e.getKey(), e.getValue());
                }
                root.add("categoryTokens", catObj);
                System.out.println(root.toString());
            } else {
                System.out.printf("Available Gift Tokens: %d\n", generalTokens);
                System.out.printf("Available Costume Tokens: %d\n", costumeTokensVal);
            }

            disconnectAndExit(0);
            return PacketSignal.HANDLED;
        }

        // --- FETCH MODE ---
        if (info.isMainForm()) {
            this.activeSubcategory = null;
            this.searchedInCurrentSubcategory = false;

            // Checker: Account does not have any gift tokens (form returns only Buy Gifts)
            if (info.hasNoGiftTokens()) {
                log.warn("Account has no gift tokens! /gift form returned only 'Buy Gifts'.");
                outputFailure("Account does not have any gift tokens!");
                disconnectAndExit(1);
                return PacketSignal.HANDLED;
            }

            if (!crawlInProgress) {
                log.info("Gifting Main Menu loaded. Token Balance: {} Tokens", info.getTokenBalance());

                crawlQueue.clear();
                crawlQueue.addAll(info.getSubcategories());
                totalSubcategoriesToCrawl = crawlQueue.size();
                completedSubcategories = 0;
                crawlInProgress = true;

                log.info("Starting automated crawl of {} sub-menus: {}", totalSubcategoriesToCrawl, crawlQueue.stream().map(CatalogManager.SubcategoryButton::getName).toList());
            } else {
                log.info("Returned to Main Menu via 'Go back'.");
            }

            if (!crawlQueue.isEmpty()) {
                currentCrawlingSubcategory = crawlQueue.poll();
                completedSubcategories++;
                this.activeSubcategory = currentCrawlingSubcategory.getName();

                int targetBtnIndex = currentCrawlingSubcategory.getButtonIndex();
                for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
                    if (sub.getName().equalsIgnoreCase(currentCrawlingSubcategory.getName())) {
                        targetBtnIndex = sub.getButtonIndex();
                        break;
                    }
                }

                log.info("Navigating to sub-menu [{}/{}] '{}' (button index {})...", completedSubcategories, totalSubcategoriesToCrawl, currentCrawlingSubcategory.getName(), targetBtnIndex);

                final int formId = packet.getFormId();
                final int btnIdx = targetBtnIndex;
                ticker.schedule(() -> clickFormButton(formId, btnIdx), 150, TimeUnit.MILLISECONDS);
            } else {
                // All sub-menus have been crawled!
                crawlInProgress = false;
                currentCrawlingSubcategory = null;
                this.activeSubcategory = null;

                log.info("All sub-menus crawled successfully! Loaded {} items across categories: {}", catalogManager.getData().getItems().size(), catalogManager.getData().getItems().values().stream().map(CatalogManager.ItemEntry::getCategory).distinct().sorted().toList());

                if (targetItem != null && !targetItem.isBlank()) {
                    boolean found = catalogManager.findItem(targetItem).isPresent();
                    System.out.println(catalogManager.getItemReport(targetItem));
                    disconnectAndExit(found ? 0 : 1);
                } else {
                    catalogManager.printCatalogSummary();
                    disconnectAndExit(0);
                }
            }
        } else {
            // Subcategory form loaded during crawl
            String categoryName = activeSubcategory != null ? activeSubcategory : info.getTitle();
            log.info("Sub-menu '{}' loaded ({} items).", categoryName, info.getItems().size());

            if (crawlInProgress) {
                int backBtnIndex = info.getGoBackButtonIndex();
                if (backBtnIndex != -1) {
                    final int formId = packet.getFormId();
                    log.info("Clicking 'Go back' button (index {}) in '{}' to return to Main Menu...", backBtnIndex, categoryName);
                    ticker.schedule(() -> clickFormButton(formId, backBtnIndex), 150, TimeUnit.MILLISECONDS);
                } else {
                    log.warn("No 'Go back' button found in '{}'. Closing form...", categoryName);
                    final int formId = packet.getFormId();
                    ticker.schedule(() -> sendModalFormResponse(formId, "null"), 150, TimeUnit.MILLISECONDS);
                }
            }
        }

        return PacketSignal.HANDLED;
    }

    private void handleGiftFlow(ModalFormRequestPacket packet, CatalogManager.ParsedFormInfo info) {
        if (recipient == null || recipient.isBlank() || targetItem == null || targetItem.isBlank()) {
            outputFailure("Missing arguments for gift! Required: gift [username] [item]");
            disconnectAndExit(1);
            return;
        }

        if (info.getTokenBalance() != -1) {
            this.cachedTokenBalance = info.getTokenBalance();
        }
        this.cachedCategoryTokens.putAll(info.getCategoryTokens());

        String rawJson = packet.getFormData();
        log.info("Gift flow form received: Form ID={} (step={})", packet.getFormId(), giftStep);

        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            String formType = root.has("type") ? root.get("type").getAsString() : "";
            String formTitle = root.has("title") ? CatalogManager.cleanFormatting(root.get("title").getAsString()) : "";
            String contentText = "";
            if (root.has("content")) {
                if (root.get("content").isJsonPrimitive()) {
                    contentText = CatalogManager.cleanFormatting(root.get("content").getAsString());
                } else if (root.get("content").isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement el : root.getAsJsonArray("content")) {
                        if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(CatalogManager.cleanFormatting(el.getAsJsonObject().get("text").getAsString()));
                        }
                    }
                    contentText = sb.toString();
                }
            }

            log.info("Gift Form Inspection: type='{}', title='{}', content='{}'",
                    formType, formTitle, contentText.replace("\n", " "));

            // =========================================================================
            // 0. ZERO TOKEN CHECK: "Buy Gifts" Form
            // In packet-logs:
            // - If Title is "Buy Gifts", Content is "You can use Minecoins to buy gifts."
            // =========================================================================
            if (formTitle.equals("Buy Gifts") || contentText.equals("You can use Minecoins to buy gifts.")) {
                log.warn("[STRICT VALIDATION] Matched 'Buy Gifts' Menu: Title='{}', Content='{}'. Zero tokens available in gift wallet.", formTitle, contentText);
                outputFailure("Account does not have any gift tokens!");
                sendModalFormResponse(packet.getFormId(), "null");
                disconnectAndExit(1);
                return;
            }

            // =========================================================================
            // 1. ABSOLUTE SAFETY GUARD: Friend List Blacklist
            // Exact form title from The Hive: "Gift to a Friend"
            // =========================================================================
            if (formTitle.equals("Gift to a Friend") || formTitle.contains("Friends")) {
                log.error("[STRICT VALIDATION] CRITICAL SAFETY TRIP: Hive opened '{}' form! Aborting immediately.", formTitle);
                outputFailure("Safety abort: Friend selection screen opened. Aborted to avoid gifting to friend list.");
                sendModalFormResponse(packet.getFormId(), "null");
                disconnectAndExit(1);
                return;
            }

            // =========================================================================
            // 2. MAIN STORE FORM
            // In packet-logs:
            // - Type: "form"
            // - Title: "Gifting"
            // - Content: "You have gifts available! You can also buy more gifts using the Buy Gifts button."
            // - Button 0: "Buy Gifts"
            // =========================================================================
            if (formTitle.equals("Gifting") && contentText.equals("You have gifts available! You can also buy more gifts using the Buy Gifts button.")) {
                int buyGiftsIdx = findButtonByExactText(root, "Buy Gifts");
                log.info("[STRICT VALIDATION] Matched Main Store Form: Title='{}', Content='{}'. Found 'Buy Gifts' button at index {}.", formTitle, contentText, buyGiftsIdx);

                // If only 1 button exists ("Buy Gifts"), account has 0 tokens
                if (root.has("buttons") && root.getAsJsonArray("buttons").size() == 1 && buyGiftsIdx != -1) {
                    log.warn("[STRICT VALIDATION] Main Store only contains 'Buy Gifts' button. Account has 0 gift tokens.");
                    outputFailure("Account does not have any gift tokens!");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }

                handleGiftMainForm(packet, info);
                return;
            }

            // =========================================================================
            // 3. COST PROCEED WARNING MODAL (Items costing > 1 token)
            // In packet-logs:
            // - Type: "modal"
            // - Title: "Gifting"
            // - Content contains: "This amount will be taken from your gift wallet after selecting and confirming a gifting method. Do you wish to proceed?"
            // - Button 1: "Yes, Continue"
            // - Button 2: "Go back"
            // =========================================================================
            if (formTitle.equals("Gifting") && "modal".equalsIgnoreCase(formType)
                    && contentText.contains("This amount will be taken from your gift wallet after selecting and confirming a gifting method. Do you wish to proceed?")) {
                String btn1 = root.has("button1") ? CatalogManager.cleanFormatting(root.get("button1").getAsString()) : "";
                String btn2 = root.has("button2") ? CatalogManager.cleanFormatting(root.get("button2").getAsString()) : "";

                if (btn1.equals("Yes, Continue") && btn2.equals("Go back")) {
                    log.info("[STRICT VALIDATION] Matched Cost Warning Modal: Title='{}', Button 1='{}', Button 2='{}'. Content verified. Confirming cost proceed (true)...", formTitle, btn1, btn2);
                    this.giftStep = GiftStep.SELECTING_GIFT_METHOD;
                    updateState(GiftStep.SELECTING_GIFT_METHOD, "Confirmed cost proceed warning modal ('Yes, Continue')");
                    ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), "true\n"), 150, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.error("[STRICT VALIDATION FAILED] Cost modal missing expected buttons ('Yes, Continue' / 'Go back'). Found: btn1='{}', btn2='{}'.", btn1, btn2);
                    outputFailure("Cost proceed modal validation failed. Expected 'Yes, Continue'.");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }
            }

            // =========================================================================
            // 4. DELIVERY METHOD SELECTION FORM
            // In packet-logs:
            // - Type: "form"
            // - Title: "Gifting"
            // - Content contains: "Decide using a button below!"
            // - Wanted Button: "Gift to a Username"
            // =========================================================================
            if (formTitle.equals("Gifting") && "form".equalsIgnoreCase(formType)
                    && contentText.contains("Decide using a button below!")) {
                int usernameBtnIdx = findButtonByExactText(root, "Gift to a Username");
                if (usernameBtnIdx != -1) {
                    log.info("[STRICT VALIDATION] Matched Delivery Method Form: Title='{}', Content verified. Found wanted button: 'Gift to a Username' at index {}.", formTitle, usernameBtnIdx);
                    this.giftStep = GiftStep.ENTERING_USERNAME;
                    updateState(GiftStep.ENTERING_USERNAME, "Selected 'Gift to a Username' option");
                    final int uIdx = usernameBtnIdx;
                    ticker.schedule(() -> clickFormButton(packet.getFormId(), uIdx), 150, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.error("[STRICT VALIDATION FAILED] Delivery Method Form missing wanted button 'Gift to a Username'.");
                    outputFailure("Could not find 'Gift to a Username' option on server.");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }
            }

            // =========================================================================
            // 5. RECIPIENT USERNAME FORM
            // In packet-logs:
            // - Type: "custom_form"
            // - Title: "Gifting"
            // - Content contains: "Pick a username below - they don't have to be online to receive your gift."
            // - Input element with type "input"
            // =========================================================================
            if (formTitle.equals("Gifting") && "custom_form".equalsIgnoreCase(formType)
                    && contentText.contains("Pick a username below - they don't have to be online to receive your gift.")) {
                JsonArray content = root.has("content") && root.get("content").isJsonArray()
                        ? root.getAsJsonArray("content")
                        : new JsonArray();
                JsonArray responseArray = new JsonArray();
                int inputIdx = -1;

                for (int i = 0; i < content.size(); i++) {
                    if (content.get(i).isJsonObject()) {
                        JsonObject elem = content.get(i).getAsJsonObject();
                        String elemType = elem.has("type") ? elem.get("type").getAsString() : "";

                        if ("input".equalsIgnoreCase(elemType)) {
                            responseArray.add(recipient);
                            inputIdx = i;
                            continue;
                        }
                    }
                    responseArray.add(JsonNull.INSTANCE);
                }

                if (inputIdx != -1) {
                    log.info("[STRICT VALIDATION] Matched Recipient Username Form: Title='{}', Content verified. Found input field at index {} for recipient '{}'. Submitting...", formTitle, inputIdx, recipient);
                    this.giftStep = GiftStep.AWAITING_CONFIRMATION_MODAL;
                    updateState(GiftStep.AWAITING_CONFIRMATION_MODAL, "Submitted recipient username '" + recipient + "'");
                    String resp = responseArray.toString() + "\n";
                    ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), resp), 150, TimeUnit.MILLISECONDS);

                    usernameTimeoutTask = ticker.schedule(() -> {
                        if (session.isConnected() && giftStep == GiftStep.AWAITING_CONFIRMATION_MODAL && !exiting.get()) {
                            outputFailure(String.format("Timed out waiting for server response after submitting username '%s'. Last state: %s",
                                    recipient, getLastKnownStateSummary()));
                            disconnectAndExit(1);
                        }
                    }, 30000, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.error("[STRICT VALIDATION FAILED] Recipient Username Form has no input element.");
                    outputFailure("No input field found in username form.");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }
            }

            // =========================================================================
            // 6. FINAL CONFIRMATION MODAL ("Send gift")
            // In packet-logs:
            // - Type: "modal"
            // - Title: "Send gift"
            // - Content contains: "Are you sure? You won't be able to undo this!"
            // - Button 1: "Send gift"
            // - Button 2: "Go back"
            // =========================================================================
            if (formTitle.equals("Send gift") && "modal".equalsIgnoreCase(formType)
                    && contentText.contains("Are you sure? You won't be able to undo this!")) {
                if (usernameTimeoutTask != null) {
                    usernameTimeoutTask.cancel(false);
                    usernameTimeoutTask = null;
                }

                String btn1 = root.has("button1") ? CatalogManager.cleanFormatting(root.get("button1").getAsString()) : "";
                String btn2 = root.has("button2") ? CatalogManager.cleanFormatting(root.get("button2").getAsString()) : "";

                if (btn1.equals("Send gift") && btn2.equals("Go back")) {
                    log.info("[STRICT VALIDATION] Matched Final Confirmation Modal: Title='{}', Content verified, Button 1='{}', Button 2='{}'. Submitting final confirmation...", formTitle, btn1, btn2);
                    this.giftStep = GiftStep.AWAITING_COMPLETION;
                    updateState(GiftStep.AWAITING_COMPLETION, "Confirmed final 'Send gift' modal");
                    ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), "true\n"), 150, TimeUnit.MILLISECONDS);

                    ticker.schedule(() -> {
                        if (session.isConnected() && !exiting.get()) {
                            outputSuccess(String.format("Gift '%s' submitted for player '%s'.", targetItem, recipient));
                            disconnectAndExit(0);
                        }
                    }, 25000, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.error("[STRICT VALIDATION FAILED] Final confirmation modal missing expected buttons ('Send gift' / 'Go back'). Found: btn1='{}', btn2='{}'.", btn1, btn2);
                    outputFailure("Final confirmation modal missing 'Send gift' button.");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }
            }

            // =========================================================================
            // SEARCH INPUT FORM
            // In packet-logs:
            // - Type: "custom_form"
            // - Title: "Gifting"
            // - Content contains: "search for available gifts"
            // =========================================================================
            if (formTitle.equals("Gifting") && "custom_form".equalsIgnoreCase(formType)
                    && contentText.contains("search for available gifts")) {
                JsonArray content = root.has("content") && root.get("content").isJsonArray()
                        ? root.getAsJsonArray("content")
                        : new JsonArray();
                JsonArray responseArray = new JsonArray();
                int inputIdx = -1;

                for (int i = 0; i < content.size(); i++) {
                    if (content.get(i).isJsonObject()) {
                        JsonObject elem = content.get(i).getAsJsonObject();
                        String elemType = elem.has("type") ? elem.get("type").getAsString() : "";
                        if ("input".equalsIgnoreCase(elemType)) {
                            responseArray.add(targetItem);
                            inputIdx = i;
                            continue;
                        }
                    }
                    responseArray.add(JsonNull.INSTANCE);
                }

                if (inputIdx != -1) {
                    log.info("[STRICT VALIDATION] Matched Search Input Form: Title='{}', Content verified. Submitting search query '{}' at input index {}...", formTitle, targetItem, inputIdx);
                    updateState(GiftStep.SEARCHING_SUBMENUS, "Submitted search term '" + targetItem + "' in subcategory '" + activeSubcategory + "'");
                    String resp = responseArray.toString() + "\n";
                    ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), resp), 150, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.error("[STRICT VALIDATION FAILED] Search Input Form has no input element.");
                    outputFailure("No input field found in search form.");
                    sendModalFormResponse(packet.getFormId(), "null");
                    disconnectAndExit(1);
                    return;
                }
            }

            // =========================================================================
            // 7. SUBCATEGORY MENU FORM
            // In packet-logs:
            // - Type: "form"
            // - Title: "Gifting"
            // - Content: ""
            // - Button: "Go back"
            // =========================================================================
            if (formTitle.equals("Gifting") && "form".equalsIgnoreCase(formType) && contentText.isEmpty()) {
                int backBtnIndex = findButtonByExactText(root, "Go back");
                if (backBtnIndex != -1) {
                    log.info("[STRICT VALIDATION] Matched Subcategory Form: Title='{}', Content='{}'. Found 'Go back' button at index {}. Active Subcategory='{}'.", formTitle, contentText, backBtnIndex, activeSubcategory);

                    int itemBtnIndex = findButtonByExactText(root, targetItem);
                    if (itemBtnIndex != -1 && itemBtnIndex != backBtnIndex) {
                        String resolvedName = getButtonNameInForm(rawJson, itemBtnIndex);
                        if (resolvedName != null) this.targetItem = resolvedName;

                        if (checkItemBalance(info, this.targetItem)) {
                            return;
                        }

                        log.info("[STRICT VALIDATION] SUCCESS: Found wanted button for item '{}' at index {} in subcategory '{}'! Clicking to select...", this.targetItem, itemBtnIndex, activeSubcategory);
                        this.giftStep = GiftStep.SELECTING_ITEM;
                        updateState(GiftStep.SELECTING_ITEM, "Found item '" + this.targetItem + "' in subcategory '" + activeSubcategory + "', clicking to select");
                        final int btnIdx = itemBtnIndex;
                        ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
                        return;
                    }

                    // Item not found directly in this subcategory. Check if 'Search' button is present and not yet searched
                    int searchBtnIndex = findButtonByExactText(root, "Search");
                    if (searchBtnIndex != -1 && !searchedInCurrentSubcategory) {
                        log.info("[STRICT VALIDATION] Item '{}' not found directly in subcategory '{}'. Found 'Search' button at index {}. Clicking to search...",
                                targetItem, activeSubcategory, searchBtnIndex);
                        searchedInCurrentSubcategory = true;
                        updateState(GiftStep.SEARCHING_SUBMENUS, "Searching for '" + targetItem + "' in subcategory '" + activeSubcategory + "'");
                        final int sIdx = searchBtnIndex;
                        ticker.schedule(() -> clickFormButton(packet.getFormId(), sIdx), 150, TimeUnit.MILLISECONDS);
                        return;
                    }

                    if (targetCategory != null && !targetCategory.isBlank()) {
                        log.warn("[STRICT VALIDATION] Wanted item '{}' not in specified subcategory '{}'. Aborting.", targetItem, targetCategory);
                        outputFailure("Item '" + targetItem + "' was not found in category '" + targetCategory + "'.");
                        sendModalFormResponse(packet.getFormId(), "null");
                        disconnectAndExit(1);
                        return;
                    }

                    log.info("[STRICT VALIDATION] Wanted item '{}' not in subcategory '{}'. Found 'Go back' at index {}. Returning to search next subcategory...", targetItem, activeSubcategory, backBtnIndex);
                    updateState(GiftStep.SEARCHING_SUBMENUS, "Item '" + targetItem + "' not in subcategory '" + activeSubcategory + "', clicking 'Go back'");
                    final int bIdx = backBtnIndex;
                    ticker.schedule(() -> clickFormButton(packet.getFormId(), bIdx), 150, TimeUnit.MILLISECONDS);
                    return;
                }
            }

            // =========================================================================
            // 8. UNRECOGNIZED FORM SAFEGUARD
            // Never guess or click index 0 on an unknown form!
            // =========================================================================
            log.error("[STRICT VALIDATION FAILED] Unrecognized form structure received: Type='{}', Title='{}', Content='{}'. Aborting safely without taking action.", formType, formTitle, contentText);
            outputFailure(String.format("Strict validation failed: Unrecognized form received from server (Type='%s', Title='%s'). Aborting safely.", formType, formTitle));
            sendModalFormResponse(packet.getFormId(), "null");
            disconnectAndExit(1);

        } catch (Exception e) {
            log.error("Error in handleGiftFlow", e);
            outputFailure("Internal error handling form: " + e.getMessage());
            disconnectAndExit(1);
        }
    }

    private void handleGiftMainForm(ModalFormRequestPacket packet, CatalogManager.ParsedFormInfo info) {
        this.searchedInCurrentSubcategory = false;
        if (info.getTokenBalance() != -1) {
            this.cachedTokenBalance = info.getTokenBalance();
        }
        this.cachedCategoryTokens.putAll(info.getCategoryTokens());

        // Checker: Account does not have any gift tokens (form returns only Buy Gifts)
        if (info.hasNoGiftTokens()) {
            log.warn("[STRICT VALIDATION] Account has no gift tokens! /gift form returned only 'Buy Gifts'. Cannot gift '{}' to '{}'.", targetItem, recipient);
            outputFailure("Account does not have any gift tokens!");
            disconnectAndExit(1);
            return;
        }

        // 1. If currently searching sub-menus:
        if (giftStep == GiftStep.SEARCHING_SUBMENUS) {
            if (!giftSearchQueue.isEmpty()) {
                CatalogManager.SubcategoryButton nextSub = giftSearchQueue.poll();
                this.activeSubcategory = nextSub.getName();
                this.searchedInCurrentSubcategory = false;

                // Lookup by name to avoid stale indices
                int targetBtnIndex = -1;
                for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
                    if (sub.getName().equalsIgnoreCase(nextSub.getName())) {
                        targetBtnIndex = sub.getButtonIndex();
                        break;
                    }
                }
                if (targetBtnIndex == -1) targetBtnIndex = nextSub.getButtonIndex();

                log.info("[STRICT VALIDATION] Next subcategory in search queue: '{}'. Found button index {}. Clicking...", nextSub.getName(), targetBtnIndex);
                updateState(GiftStep.SEARCHING_SUBMENUS, "Opening subcategory '" + nextSub.getName() + "' (button " + targetBtnIndex + ") from Main Store");
                final int btnIdx = targetBtnIndex;
                ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
                return;
            } else {
                // All sub-menus have been checked and target item was not found anywhere
                log.warn("[STRICT VALIDATION] All sub-menus searched. Item '{}' was not found anywhere.", targetItem);
                outputFailure("Item '" + targetItem + "' was not found in store or any sub-menu.");
                disconnectAndExit(1);
                return;
            }
        }

        // 2. Check if item is directly in Main Store
        JsonObject root = null;
        try {
            root = JsonParser.parseString(packet.getFormData()).getAsJsonObject();
        } catch (Exception ignored) {}

        int directBtnIndex = findButtonByExactText(root, targetItem);
        boolean isSubcategory = false;
        for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
            if (sub.getButtonIndex() == directBtnIndex) {
                isSubcategory = true;
                break;
            }
        }

        // Direct category specified:
        if (targetCategory != null && !targetCategory.isBlank()) {
            if (targetCategory.equalsIgnoreCase("Main Store")) {
                if (directBtnIndex != -1 && !isSubcategory) {
                    String resolvedName = getButtonNameInForm(packet.getFormData(), directBtnIndex);
                    if (resolvedName != null) this.targetItem = resolvedName;

                    if (checkItemBalance(info, this.targetItem)) {
                        return;
                    }

                    log.info("[STRICT VALIDATION] SUCCESS: Found wanted item '{}' directly in Main Store at index {}. Clicking to select...", this.targetItem, directBtnIndex);
                    this.giftStep = GiftStep.SELECTING_ITEM;
                    updateState(GiftStep.SELECTING_ITEM, "Found item '" + this.targetItem + "' directly in Main Store (button " + directBtnIndex + "), clicking to select");
                    final int btnIdx = directBtnIndex;
                    ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    log.warn("[STRICT VALIDATION] Item '{}' was not found directly in Main Store as requested by category.", targetItem);
                    outputFailure("Item '" + targetItem + "' was not found in Main Store.");
                    disconnectAndExit(1);
                    return;
                }
            }

            CatalogManager.SubcategoryButton matchedSub = null;
            for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
                if (sub.getName().equalsIgnoreCase(targetCategory)
                        || sub.getName().toLowerCase().contains(targetCategory.toLowerCase())) {
                    matchedSub = sub;
                    break;
                }
            }

            if (matchedSub == null) {
                log.warn("[STRICT VALIDATION] Target category '{}' was not found on server.", targetCategory);
                outputFailure("Category '" + targetCategory + "' was not found on server.");
                disconnectAndExit(1);
                return;
            }

            log.info("[STRICT VALIDATION] Target category specified: '{}'. Opening subcategory button index {} directly...",
                    matchedSub.getName(), matchedSub.getButtonIndex());
            this.giftStep = GiftStep.SEARCHING_SUBMENUS;
            this.activeSubcategory = matchedSub.getName();
            this.searchedInCurrentSubcategory = false;
            this.giftSearchQueue.clear();
            final int btnIdx = matchedSub.getButtonIndex();
            updateState(GiftStep.SEARCHING_SUBMENUS, "Opening specified subcategory '" + matchedSub.getName() + "' (button " + btnIdx + ")");
            ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
            return;
        }

        if (directBtnIndex != -1 && !isSubcategory) {
            String resolvedName = getButtonNameInForm(packet.getFormData(), directBtnIndex);
            if (resolvedName != null) this.targetItem = resolvedName;

            if (checkItemBalance(info, this.targetItem)) {
                return;
            }

            log.info("[STRICT VALIDATION] SUCCESS: Found wanted item '{}' directly in Main Store at index {}. Clicking to select...", this.targetItem, directBtnIndex);
            this.giftStep = GiftStep.SELECTING_ITEM;
            updateState(GiftStep.SELECTING_ITEM, "Found item '" + this.targetItem + "' directly in Main Store (button " + directBtnIndex + "), clicking to select");
            final int btnIdx = directBtnIndex;
            ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
            return;
        }

        // 3. Item not directly in Main Store: queue and crawl sub-menus dynamically!
        giftSearchQueue.clear();
        giftSearchQueue.addAll(info.getSubcategories());
        if (giftSearchQueue.isEmpty()) {
            if (info.hasNoGiftTokens() || cachedTokenBalance == 0) {
                outputFailure("Account does not have any gift tokens!");
            } else {
                outputFailure("Item '" + targetItem + "' was not found in store or any sub-menu.");
            }
            disconnectAndExit(1);
            return;
        }

        this.giftStep = GiftStep.SEARCHING_SUBMENUS;
        CatalogManager.SubcategoryButton firstSub = giftSearchQueue.poll();
        this.activeSubcategory = firstSub.getName();
        this.searchedInCurrentSubcategory = false;

        int firstIdx = -1;
        for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
            if (sub.getName().equalsIgnoreCase(firstSub.getName())) {
                firstIdx = sub.getButtonIndex();
                break;
            }
        }
        if (firstIdx == -1) firstIdx = firstSub.getButtonIndex();

        log.info("[STRICT VALIDATION] Item '{}' not directly in Main Store. Queued {} subcategories. Opening first subcategory '{}' at button index {}...",
                targetItem, info.getSubcategories().size(), firstSub.getName(), firstIdx);
        updateState(GiftStep.SEARCHING_SUBMENUS, "Item '" + targetItem + "' not directly in Main Store, opening subcategory '" + firstSub.getName() + "' (button " + firstIdx + ")");
        final int btnIdx = firstIdx;
        ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
    }

    private int findButtonByExactText(JsonObject root, String exactText) {
        if (root == null || !root.has("buttons") || !root.get("buttons").isJsonArray() || exactText == null) return -1;
        JsonArray buttons = root.getAsJsonArray("buttons");
        String target = exactText.trim();
        for (int i = 0; i < buttons.size(); i++) {
            String raw = buttons.get(i).getAsJsonObject().get("text").getAsString();
            String clean = CatalogManager.cleanFormatting(raw);
            String line0 = clean.split("\n")[0].replaceFirst("(?i)^NEW\\s+", "").trim();
            if (line0.equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private String getButtonNameInForm(String formJson, int buttonIndex) {
        if (formJson == null || buttonIndex < 0) return null;
        try {
            JsonObject root = JsonParser.parseString(formJson).getAsJsonObject();
            if (root.has("buttons") && root.get("buttons").isJsonArray()) {
                JsonArray buttons = root.getAsJsonArray("buttons");
                if (buttonIndex < buttons.size()) {
                    String raw = buttons.get(buttonIndex).getAsJsonObject().get("text").getAsString();
                    String clean = CatalogManager.cleanFormatting(raw);
                    return clean.split("\n")[0].replaceFirst("(?i)^NEW\\s+", "").trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean checkItemBalance(CatalogManager.ParsedFormInfo info, String itemName) {
        if (itemName == null) return false;
        String query = itemName.trim();
        int cost = 1;
        for (CatalogManager.ItemEntry item : info.getItems()) {
            if (item.getName().equalsIgnoreCase(query)) {
                cost = item.getTokenCost();
                break;
            }
        }
        this.targetItemCost = cost;

        int availableBalance = -1;
        if (activeSubcategory != null && cachedCategoryTokens.containsKey(activeSubcategory)) {
            availableBalance = cachedCategoryTokens.get(activeSubcategory);
        } else if (cachedTokenBalance != -1) {
            availableBalance = cachedTokenBalance;
        }

        if (availableBalance != -1 && cost > 0 && availableBalance < cost) {
            String catMsg = (activeSubcategory != null && !activeSubcategory.isBlank())
                    ? " in " + activeSubcategory
                    : "";
            outputFailure(String.format("Insufficient tokens%s! Available: %d, Required: %d.",
                    catMsg, availableBalance, cost));
            disconnectAndExit(1);
            return true;
        }
        return false;
    }

    public void sendModalFormResponse(int formId, String formData) {
        ModalFormResponsePacket response = new ModalFormResponsePacket();
        response.setFormId(formId);
        response.setFormData(formData);
        response.setCancelReason(Optional.empty());
        session.sendPacketImmediately(response);
    }

    public void clickFormButton(int formId, int buttonIndex) {
        sendModalFormResponse(formId, buttonIndex + "\n");
    }

    @Override
    public PacketSignal handle(TextPacket packet) {
        String msg = packet.getMessage() != null ? packet.getMessage().toString() : "";
        String clean = CatalogManager.cleanFormatting(msg);
        List<String> params = packet.getParameters();

        // Build combined text including parameters (e.g. for TRANSLATION packets)
        StringBuilder sb = new StringBuilder(clean);
        if (params != null && !params.isEmpty()) {
            for (String p : params) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(CatalogManager.cleanFormatting(p));
            }
        }
        String combined = sb.toString().trim();
        String lower = combined.toLowerCase();

        log.info("Server TextPacket [type={}, source='{}', xuid='{}']: '{}'",
                packet.getType(), packet.getSourceName(), packet.getXuid(), combined);

        // Filter out player chat originating from players in the hub
        if (isPlayerMessage(packet, combined)) {
            return PacketSignal.HANDLED;
        }

        this.lastServerMessage = combined;
        updateState("Received server text message: '" + combined + "'");

        if (usernameTimeoutTask != null) {
            usernameTimeoutTask.cancel(false);
            usernameTimeoutTask = null;
        }

        if (mode == GiBot.BotMode.GIFT && giftStep != GiftStep.FINISHED) {
            String recLower = recipient != null ? recipient.toLowerCase().trim() : "";
            String itemLower = targetItem != null ? targetItem.toLowerCase().trim() : "";

            // 1. Player not found: "Sorry, we can't find a player named {name}"
            if (lower.contains("sorry, we can't find a player named " + recLower)
                    || (lower.contains("sorry, we can't find a player named") && lower.contains(recLower))
                    || lower.contains("can't find a player")) {
                if (giftRetryTask != null) {
                    giftRetryTask.cancel(false);
                    giftRetryTask = null;
                }
                log.warn("[STRICT TEXT VALIDATION] Hive chat error: Player '{}' not found: '{}'", recipient, combined);
                outputFailure(combined);
                disconnectAndExit(1);
                return PacketSignal.HANDLED;
            }

            // 2. Player already has the item: "{name} already has the {item}"
            if ((!recLower.isEmpty() && !itemLower.isEmpty() && lower.contains(recLower + " already has the " + itemLower))
                    || (lower.contains(recLower) && lower.contains("already has the") && lower.contains(itemLower))
                    || lower.contains(recLower + " already has the")
                    || lower.contains("already has the " + itemLower)
                    || lower.contains("already has the")
                    || lower.contains("already has")
                    || lower.contains("already owns")) {
                if (giftRetryTask != null) {
                    giftRetryTask.cancel(false);
                    giftRetryTask = null;
                }
                log.warn("[STRICT TEXT VALIDATION] Hive chat error: Recipient '{}' already has item '{}': '{}'", recipient, targetItem, combined);
                outputFailure(combined);
                disconnectAndExit(1);
                return PacketSignal.HANDLED;
            }

            // 3. Gift success: "You've gifted {item} to {player}. We're sure they will love it!"
            if ((lower.contains("you've gifted") && lower.contains("we're sure they will love it!"))
                    || (lower.contains("you've gifted " + itemLower + " to " + recLower))
                    || (lower.contains("you've gifted") && lower.contains("to " + recLower))) {
                if (giftRetryTask != null) {
                    giftRetryTask.cancel(false);
                    giftRetryTask = null;
                }
                log.info("[STRICT TEXT VALIDATION] Hive chat SUCCESS: '{}'", combined);
                outputSuccess(combined);
                this.giftStep = GiftStep.FINISHED;
                disconnectAndExit(0);
                return PacketSignal.HANDLED;
            }

            // 4. Other server rejections
            if (lower.contains("cannot receive")
                    || lower.contains("not eligible")
                    || lower.contains("cannot be gifted")
                    || lower.contains("failed to gift")
                    || lower.contains("you cannot use")
                    || lower.contains("gifting is currently disabled")
                    || lower.contains("please wait before")) {
                if (giftRetryTask != null) {
                    giftRetryTask.cancel(false);
                    giftRetryTask = null;
                }
                log.warn("[STRICT TEXT VALIDATION] Hive chat rejection: '{}'", combined);
                outputFailure(combined);
                disconnectAndExit(1);
                return PacketSignal.HANDLED;
            }
        }

        return PacketSignal.HANDLED;
    }

    private boolean isPlayerMessage(TextPacket packet, String combined) {
        if (packet == null) return false;

        // Player chat originated by players on Bedrock has an Xbox User ID (XUID) assigned.
        // The Hive sets xuid="0" or empty/null for server/system broadcast packets.
        String xuid = packet.getXuid();
        if (xuid != null && !xuid.isBlank() && !xuid.equals("0")) {
            return true;
        }

        // Vanilla player chat format: <PlayerName> text
        if (combined.startsWith("<") && combined.contains(">")) {
            return true;
        }

        // The Hive hub player chat format: [12] PlayerName: text or PlayerName [Rank] » text
        if (combined.matches("^\\[\\d+\\]\\s+[^:]+:.*") || combined.contains(" » ")) {
            return true;
        }

        return false;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        String kickMsg = packet.getKickMessage() != null ? CatalogManager.cleanFormatting(packet.getKickMessage()) : "Disconnected by server";
        log.warn("Disconnected by server: {}", kickMsg);
        this.lastServerMessage = kickMsg;
        updateState("Server sent disconnect packet: '" + kickMsg + "'");
        outputFailure("Disconnected by server: " + kickMsg);
        disconnectAndExit(1);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(SetTitlePacket packet) {
        log.info("Server SetTitlePacket [type={}]: text='{}'", packet.getType(), packet.getText());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ToastRequestPacket packet) {
        log.info("Server ToastRequestPacket: title='{}', content='{}'", packet.getTitle(), packet.getContent());
        this.lastServerMessage = packet.getTitle() + " - " + packet.getContent();
        updateState("Received toast notification: '" + this.lastServerMessage + "'");
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        log.warn("Session disconnected: {}", reason);
        updateState("Session disconnected: " + reason);
        if (!exiting.get()) {
            outputFailure("Network session disconnected: " + reason);
            disconnectAndExit(1);
        }
        ticker.shutdown();
    }

    private void attemptGiftCommand() {
        if (!session.isConnected() || exiting.get()) return;
        giftCommandAttempts++;
        log.info("Executing /gift command (attempt {})...", giftCommandAttempts);
        updateState("Executed /gift command (attempt " + giftCommandAttempts + ")");
        sendCommand("/gift");
    }

    /**
     * Minimal keepalive ticker (20 TPS) to prevent server timeout, and triggers /gift.
     */
    private void startTicking() {
        if (!spawned.compareAndSet(false, true)) {
            return;
        }

        log.info("Bot initialized. Session keepalive started.");

        ticker.scheduleAtFixedRate(() -> {
            try {
                if (!session.isConnected()) {
                    ticker.shutdown();
                    return;
                }

                tick++;

                PlayerAuthInputPacket input = new PlayerAuthInputPacket();
                input.setTick(tick);
                input.setPosition(currentPosition);
                input.setRotation(Vector3f.from(pitch, yaw, yaw));
                input.setDelta(Vector3f.ZERO);
                input.setMotion(Vector2f.ZERO);
                input.setInputMode(InputMode.MOUSE);
                input.setPlayMode(ClientPlayMode.NORMAL);
                input.setInputInteractionModel(InputInteractionModel.TOUCH);
                input.setInteractRotation(Vector2f.from(pitch, yaw));
                input.setAnalogMoveVector(Vector2f.ZERO);
                input.setRawMoveVector(Vector2f.ZERO);

                input.getInputData().add(PlayerAuthInputData.BLOCK_BREAKING_DELAY_ENABLED);
                input.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);

                if (pendingTeleport) {
                    input.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
                    pendingTeleport = false;
                }

                session.sendPacket(input);
            } catch (Exception e) {
                log.error("Error in keepalive ticker", e);
            }
        }, 500, 50, TimeUnit.MILLISECONDS);

        // Schedule initial /gift command 1.5 seconds after spawning into the world
        ticker.schedule(this::attemptGiftCommand, 1500, TimeUnit.MILLISECONDS);

        // Retry sending /gift up to 5 times (every 8s) if still in IDLE step
        giftRetryTask = ticker.scheduleAtFixedRate(() -> {
            if (giftStep == GiftStep.IDLE && !exiting.get()) {
                if (giftCommandAttempts < 5) {
                    log.info("No response to /gift yet. Retrying /gift command (attempt {}/5)...", giftCommandAttempts + 1);
                    attemptGiftCommand();
                } else {
                    log.warn("Sent /gift 5 times without form response. Waiting for server response or watchdog...");
                    if (giftRetryTask != null) {
                        giftRetryTask.cancel(false);
                    }
                }
            } else {
                if (giftRetryTask != null) {
                    giftRetryTask.cancel(false);
                }
            }
        }, 8000, 8000, TimeUnit.MILLISECONDS);
    }

    public void sendCommand(String commandLine) {
        if (!commandLine.startsWith("/")) {
            commandLine = "/" + commandLine;
        }
        CommandRequestPacket packet = new CommandRequestPacket();
        packet.setCommand(commandLine);
        packet.setCommandOriginData(new CommandOriginData(CommandOriginType.PLAYER, UUID.randomUUID(), "", 0));
        packet.setInternal(false);
        packet.setVersion(session.getCodec().getProtocolVersion());
        session.sendPacketImmediately(packet);
        log.info("Sent command: {}", commandLine);
    }

    private JSONObject loadSkinData() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/skinData.json")) {
            if (is == null) {
                throw new IllegalStateException("Embedded /skinData.json resource not found!");
            }
            String skinString = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new JSONObject(JsonUtil.parseJson(skinString));
        }
    }
}
