package com.ihsanharh.gibot;

import com.google.gson.JsonArray;
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
import java.util.Base64;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private long runtimeEntityId;
    private Vector3f currentPosition = Vector3f.ZERO;
    private float yaw = 0f;
    private float pitch = 0f;
    private long tick = 0;

    private volatile boolean pendingTeleport = false;

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean spawned = new AtomicBoolean(false);
    private final boolean jsonOutput;
    private final AtomicBoolean outputPrinted = new AtomicBoolean(false);

    public BotPacketHandler(BedrockClientSession session, Account account, SocketAddress serverAddress, CatalogManager catalogManager, GiBot.BotMode mode, String recipient, String targetItem, boolean jsonOutput) {
        this.session = session;
        this.account = account;
        this.serverAddress = serverAddress;
        this.catalogManager = catalogManager;
        this.mode = mode;
        this.recipient = recipient;
        this.targetItem = targetItem;
        this.jsonOutput = jsonOutput;
    }

    public void outputSuccess(String message) {
        if (!outputPrinted.compareAndSet(false, true)) return;
        int remainingTokens = -1;
        if (cachedTokenBalance != -1) {
            int cost = (targetItemCost > 0) ? targetItemCost : 1;
            remainingTokens = Math.max(0, cachedTokenBalance - cost);
        }

        if (jsonOutput) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "success");
            if (mode == GiBot.BotMode.GIFT) {
                obj.addProperty("recipient", recipient);
                obj.addProperty("item", targetItem);
                if (remainingTokens != -1) {
                    obj.addProperty("remainingTokens", remainingTokens);
                    obj.addProperty("remainingBalance", remainingTokens);
                }
            }
            obj.addProperty("message", message);
            System.out.println(obj.toString());
        } else {
            if (mode == GiBot.BotMode.GIFT && remainingTokens != -1) {
                System.out.println(String.format("Success: %s (Remaining Tokens: %d)", message, remainingTokens));
            } else {
                System.out.println("Success: " + message);
            }
        }
    }

    public void outputFailure(String error) {
        if (!outputPrinted.compareAndSet(false, true)) return;
        if (jsonOutput) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "error");
            obj.addProperty("message", error);
            System.out.println(obj.toString());
        } else {
            System.out.println("Failed: " + error);
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
            ClientCacheStatusPacket cacheStatus = new ClientCacheStatusPacket();
            cacheStatus.setSupported(false);
            session.sendPacketImmediately(cacheStatus);
        } else if (packet.getStatus() == PlayStatusPacket.Status.PLAYER_SPAWN) {
            log.info("Bot spawned in world.");

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
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        session.sendPacketImmediately(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackStackPacket packet) {
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

    public enum GiftStep {
        IDLE,
        SEARCHING_SUBMENUS,
        SELECTING_ITEM,
        ENTERING_USERNAME,
        CONFIRMING_GIFT,
        FINISHED
    }

    private GiftStep giftStep = GiftStep.IDLE;
    private final Queue<CatalogManager.SubcategoryButton> giftSearchQueue = new LinkedList<>();
    private final Queue<CatalogManager.SubcategoryButton> crawlQueue = new LinkedList<>();
    private CatalogManager.SubcategoryButton currentCrawlingSubcategory = null;
    private boolean crawlInProgress = false;
    private int totalSubcategoriesToCrawl = 0;
    private int completedSubcategories = 0;
    private String activeSubcategory = null;
    private int cachedTokenBalance = -1;
    private int targetItemCost = 0;
    private final AtomicBoolean exiting = new AtomicBoolean(false);

    public void disconnectAndExit(int exitCode) {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        log.info("Bot job finished. Disconnecting from server (exit code {})...", exitCode);
        ticker.schedule(() -> {
            try {
                session.disconnect("Job complete");
            } catch (Exception ignored) {}
            ticker.schedule(() -> System.exit(exitCode), 400, TimeUnit.MILLISECONDS);
        }, 300, TimeUnit.MILLISECONDS);
    }

    @Override
    public PacketSignal handle(ModalFormRequestPacket packet) {
        String rawJson = packet.getFormData();
        log.info("RAW FORM RECEIVED [id={}]: {}", packet.getFormId(), rawJson);
        CatalogManager.ParsedFormInfo info = catalogManager.processForm(rawJson, account.getDisplayName(), activeSubcategory);

        if (mode == GiBot.BotMode.GIFT) {
            if (info.isMainForm()) {
                handleGiftMainForm(packet, info);
            } else {
                handleGiftSubsequentForm(packet, info);
            }
            return PacketSignal.HANDLED;
        }

        // --- FETCH MODE ---
        if (info.isMainForm()) {
            this.activeSubcategory = null;

            // Checker: Account does not have any gift tokens (form returns only Buy Gifts)
            if (info.hasNoGiftTokens() || (info.getTokenBalance() != -1 && info.getTokenBalance() <= 0)) {
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
                    ticker.schedule(() -> clickFormButton(formId, 0), 150, TimeUnit.MILLISECONDS);
                }
            }
        }

        return PacketSignal.HANDLED;
    }

    private void handleGiftMainForm(ModalFormRequestPacket packet, CatalogManager.ParsedFormInfo info) {
        if (recipient == null || recipient.isBlank() || targetItem == null || targetItem.isBlank()) {
            outputFailure("Missing arguments for gift! Required: gift [username] [item]");
            disconnectAndExit(1);
            return;
        }

        if (info.getTokenBalance() != -1) {
            this.cachedTokenBalance = info.getTokenBalance();
        }

        // Checker: Account does not have any gift tokens (form returns only Buy Gifts)
        if (info.hasNoGiftTokens() || (cachedTokenBalance != -1 && cachedTokenBalance <= 0)) {
            log.warn("Account has no gift tokens! /gift form returned only 'Buy Gifts'. Cannot gift '{}' to '{}'.", targetItem, recipient);
            outputFailure("Account does not have any gift tokens!");
            disconnectAndExit(1);
            return;
        }

        // 1. If currently searching sub-menus:
        if (giftStep == GiftStep.SEARCHING_SUBMENUS) {
            if (!giftSearchQueue.isEmpty()) {
                CatalogManager.SubcategoryButton nextSub = giftSearchQueue.poll();
                this.activeSubcategory = nextSub.getName();
                log.info("Searching next sub-menu '{}' (button index {})...", nextSub.getName(), nextSub.getButtonIndex());
                final int btnIdx = nextSub.getButtonIndex();
                ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
                return;
            } else {
                // All sub-menus have been checked and target item was not found anywhere
                outputFailure("Item '" + targetItem + "' was not found in store or any sub-menu.");
                disconnectAndExit(1);
                return;
            }
        }

        // 2. First arrival at Main Store: check if item is directly in Main Store
        int directBtnIndex = findButtonIndexInForm(packet.getFormData(), targetItem);
        boolean isSubcategory = false;
        for (CatalogManager.SubcategoryButton sub : info.getSubcategories()) {
            if (sub.getButtonIndex() == directBtnIndex) {
                isSubcategory = true;
                break;
            }
        }

        if (directBtnIndex != -1 && !isSubcategory) {
            String resolvedName = getButtonNameInForm(packet.getFormData(), directBtnIndex);
            if (resolvedName != null) this.targetItem = resolvedName;

            if (checkItemBalance(info, this.targetItem)) {
                return;
            }

            log.info("Item '{}' found directly in Main Store at index {}. Clicking to select...", this.targetItem, directBtnIndex);
            this.giftStep = GiftStep.SELECTING_ITEM;
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
        log.info("Item not in Main Store. Searching {} sub-menus starting with '{}' (button index {})...", info.getSubcategories().size(), firstSub.getName(), firstSub.getButtonIndex());
        final int firstIdx = firstSub.getButtonIndex();
        ticker.schedule(() -> clickFormButton(packet.getFormId(), firstIdx), 150, TimeUnit.MILLISECONDS);
    }

    private void handleGiftSubsequentForm(ModalFormRequestPacket packet, CatalogManager.ParsedFormInfo info) {
        String rawJson = packet.getFormData();
        log.info("Gift form received [Step: {}, Form ID: {}]", giftStep, packet.getFormId());

        if (giftStep == GiftStep.SEARCHING_SUBMENUS) {
            int itemBtnIndex = findButtonIndexInForm(rawJson, targetItem);
            int backBtnIndex = info.getGoBackButtonIndex();

            if (itemBtnIndex != -1 && itemBtnIndex != backBtnIndex) {
                String resolvedName = getButtonNameInForm(rawJson, itemBtnIndex);
                if (resolvedName != null) this.targetItem = resolvedName;

                if (checkItemBalance(info, this.targetItem)) {
                    return;
                }

                log.info("Found item '{}' in sub-menu '{}' at button index {}. Clicking item to select...",
                        this.targetItem, activeSubcategory, itemBtnIndex);
                this.giftStep = GiftStep.SELECTING_ITEM;
                final int btnIdx = itemBtnIndex;
                ticker.schedule(() -> clickFormButton(packet.getFormId(), btnIdx), 150, TimeUnit.MILLISECONDS);
                return;
            } else {
                int backIdx = backBtnIndex != -1 ? backBtnIndex : 0;
                log.info("Item '{}' not in sub-menu '{}'. Clicking 'Go back' (button index {})...",
                        targetItem, activeSubcategory, backIdx);
                ticker.schedule(() -> clickFormButton(packet.getFormId(), backIdx), 150, TimeUnit.MILLISECONDS);
                return;
            }
        }

        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            String formType = root.has("type") ? root.get("type").getAsString() : "";
            String formTitle = root.has("title") ? CatalogManager.cleanFormatting(root.get("title").getAsString()) : "";

            log.info("Gift flow form metadata: type='{}', title='{}'", formType, formTitle);

            // Step 1: After clicking item, Hive asks how you want to gift (Friend, Username, Hub, Self)
            if (giftStep == GiftStep.SELECTING_ITEM && "form".equalsIgnoreCase(formType)) {
                JsonArray buttons = root.has("buttons") ? root.getAsJsonArray("buttons") : new JsonArray();
                int usernameBtnIdx = -1;
                for (int i = 0; i < buttons.size(); i++) {
                    String btnText = CatalogManager.cleanFormatting(buttons.get(i).getAsJsonObject().get("text").getAsString());
                    log.info("Gift Method Button [{}]: '{}'", i, btnText);
                    String lower = btnText.toLowerCase();
                    if (lower.contains("username") || lower.contains("player") || lower.contains("gamertag")) {
                        usernameBtnIdx = i;
                        break;
                    }
                }

                if (usernameBtnIdx == -1 && buttons.size() > 1) {
                    String btn1Text = CatalogManager.cleanFormatting(buttons.get(1).getAsJsonObject().get("text").getAsString()).toLowerCase();
                    if (!btn1Text.contains("self") && !btn1Text.contains("myself")) {
                        usernameBtnIdx = 1; // Default to button 1 "Gift to a Username" if not self
                    }
                }

                if (usernameBtnIdx != -1) {
                    log.info("Clicking 'Gift to a Username' (button index {})...", usernameBtnIdx);
                    this.giftStep = GiftStep.ENTERING_USERNAME;
                    final int uIdx = usernameBtnIdx;
                    ticker.schedule(() -> clickFormButton(packet.getFormId(), uIdx), 150, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    outputFailure("Could not find 'Gift to a Username' option on server. Buttons: " + buttons);
                    disconnectAndExit(1);
                    return;
                }
            }

            // Step 2: Custom form requesting recipient username
            if ("custom_form".equalsIgnoreCase(formType)) {
                JsonArray content = root.has("content") ? root.getAsJsonArray("content") : new JsonArray();
                JsonArray responseArray = new JsonArray();
                boolean inputFilled = false;

                for (int i = 0; i < content.size(); i++) {
                    JsonObject elem = content.get(i).getAsJsonObject();
                    String elemType = elem.has("type") ? elem.get("type").getAsString() : "";
                    String elemText = elem.has("text") ? CatalogManager.cleanFormatting(elem.get("text").getAsString()) : "";
                    log.info("Custom Form Element [{}]: type='{}', text='{}'", i, elemType, elemText);

                    if ("input".equalsIgnoreCase(elemType)) {
                        log.info("Submitting recipient gamertag: '{}'", recipient);
                        responseArray.add(recipient);
                        inputFilled = true;
                    } else {
                        responseArray.add(JsonNull.INSTANCE);
                    }
                }

                if (inputFilled) {
                    this.giftStep = GiftStep.CONFIRMING_GIFT;
                    String resp = responseArray.toString() + "\n";
                    log.info("Sending username input response (Form ID {}): {}", packet.getFormId(), resp.trim());
                    ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), resp), 150, TimeUnit.MILLISECONDS);
                    // Safety timeout in case Hive does not respond to username input
                    ticker.schedule(() -> {
                        if (session.isConnected() && giftStep == GiftStep.CONFIRMING_GIFT && !exiting.get()) {
                            outputFailure("Timed out waiting for server response.");
                            disconnectAndExit(1);
                        }
                    }, 10000, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    outputFailure("No input field found in username form.");
                    disconnectAndExit(1);
                    return;
                }
            }

            // Step 3: Hive responds to username input (either error message or confirmation)
            if ("modal".equalsIgnoreCase(formType)) {
                String promptText = root.has("content") ? CatalogManager.cleanFormatting(root.get("content").getAsString()) : "";
                log.info("Confirmation dialog received: title='{}', content='{}'", formTitle, promptText);

                String lowerPrompt = promptText.toLowerCase();
                String lowerTitle = formTitle.toLowerCase();
                if (lowerPrompt.contains("already has") || lowerPrompt.contains("already owns")
                        || lowerPrompt.contains("cannot") || lowerPrompt.contains("can't")
                        || lowerPrompt.contains("not found") || lowerPrompt.contains("sorry")
                        || lowerTitle.contains("error")) {
                    log.warn("Hive modal rejection dialog: title='{}', prompt='{}'", formTitle, promptText);
                    outputFailure(promptText.isBlank() ? formTitle : promptText);
                    disconnectAndExit(1);
                    return;
                }

                log.info("Submitting confirmation ('Yes' / true)...");
                this.giftStep = GiftStep.CONFIRMING_GIFT;
                ticker.schedule(() -> sendModalFormResponse(packet.getFormId(), "true\n"), 150, TimeUnit.MILLISECONDS);
                // Fallback in case chat packet doesn't arrive (wait up to 15s to capture all packets/forms)
                ticker.schedule(() -> {
                    if (session.isConnected()) {
                        outputSuccess(String.format("Gift '%s' submitted for player '%s'.", targetItem, recipient));
                        disconnectAndExit(0);
                    }
                }, 15000, TimeUnit.MILLISECONDS);
                return;
            } else if ("form".equalsIgnoreCase(formType)) {
                JsonArray buttons = root.has("buttons") ? root.getAsJsonArray("buttons") : new JsonArray();
                int confirmBtnIdx = -1;
                for (int i = 0; i < buttons.size(); i++) {
                    String btnText = CatalogManager.cleanFormatting(buttons.get(i).getAsJsonObject().get("text").getAsString());
                    log.info("Form Button [{}]: '{}'", i, btnText);
                    String lowerBtn = btnText.toLowerCase();
                    if (lowerBtn.contains("confirm")
                            || lowerBtn.contains("yes")
                            || lowerBtn.contains("send gift")
                            || lowerBtn.contains("gift")
                            || lowerBtn.contains("buy")) {
                        confirmBtnIdx = i;
                        break;
                    }
                }

                if (confirmBtnIdx != -1) {
                    log.info("Found confirmation button at index {}. Confirming gift...", confirmBtnIdx);
                    this.giftStep = GiftStep.CONFIRMING_GIFT;
                    final int cIdx = confirmBtnIdx;
                    ticker.schedule(() -> clickFormButton(packet.getFormId(), cIdx), 150, TimeUnit.MILLISECONDS);
                    // Fallback in case chat packet doesn't arrive (wait up to 15s to capture all packets/forms)
                    ticker.schedule(() -> {
                        if (session.isConnected()) {
                            outputSuccess(String.format("Gift '%s' submitted for player '%s'.", targetItem, recipient));
                            disconnectAndExit(0);
                        }
                    }, 15000, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    String content = root.has("content") ? CatalogManager.cleanFormatting(root.get("content").getAsString()) : "";
                    String reason = !content.isBlank() ? content : (!formTitle.isBlank() ? formTitle : "Received form without confirmation button: " + rawJson);
                    log.warn("Form rejected / no confirmation button found: {}", reason);
                    outputFailure(reason);
                    disconnectAndExit(1);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Error processing gift form", e);
            outputFailure("Error processing gift form: " + e.getMessage());
            disconnectAndExit(1);
        }
    }

    private int findButtonIndexInForm(String formJson, String targetName) {
        if (formJson == null || formJson.isBlank() || targetName == null) return -1;
        try {
            JsonObject root = JsonParser.parseString(formJson).getAsJsonObject();
            if (!root.has("buttons") || !root.get("buttons").isJsonArray()) return -1;
            JsonArray buttons = root.getAsJsonArray("buttons");
            String query = targetName.trim();

            // Exact first line match only (letter-by-letter, case-insensitive)
            for (int i = 0; i < buttons.size(); i++) {
                String raw = buttons.get(i).getAsJsonObject().get("text").getAsString();
                String clean = CatalogManager.cleanFormatting(raw);
                String line0 = clean.split("\n")[0].replaceFirst("(?i)^NEW\\s+", "").trim();
                if (isNavigationButton(line0)) continue;
                if (line0.equalsIgnoreCase(query)) {
                    return i;
                }
            }
        } catch (Exception e) {
            log.error("Error finding button index for '{}'", targetName, e);
        }
        return -1;
    }

    private boolean isNavigationButton(String text) {
        if (text == null) return true;
        String t = text.trim().toLowerCase();
        return t.contains("go back") || t.contains("back") || t.contains("buy gifts");
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
        for (CatalogManager.ItemEntry item : info.getItems()) {
            if (item.getName().equalsIgnoreCase(query)) {
                this.targetItemCost = item.getTokenCost();
                if (cachedTokenBalance != -1 && item.getTokenCost() > 0 && cachedTokenBalance < item.getTokenCost()) {
                    outputFailure(String.format("Insufficient tokens! Available: %d, Required: %d.",
                            cachedTokenBalance, item.getTokenCost()));
                    disconnectAndExit(1);
                    return true;
                }
                break;
            }
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
        log.info("Server TextPacket [type={}]: {}", packet.getType(), packet.getMessage());
        if (packet.getType() == TextPacket.Type.CHAT) return PacketSignal.HANDLED;

        if (mode == GiBot.BotMode.GIFT && giftStep != GiftStep.IDLE && giftStep != GiftStep.FINISHED) {
            String msg = packet.getMessage() != null ? packet.getMessage().toString() : "";
            String clean = CatalogManager.cleanFormatting(msg).toLowerCase();

            if (clean.contains("sorry, we can't find a player named")
                    || clean.contains("can't find a player")
                    || clean.contains("already has")
                    || clean.contains("already owns")
                    || clean.contains("cannot receive")
                    || clean.contains("not eligible")
                    || clean.contains("cannot be gifted")
                    || clean.contains("failed to gift")) {
                log.info("Hive chat rejection: {}", msg);
                outputFailure(CatalogManager.cleanFormatting(msg));
                disconnectAndExit(1);
            } else if (clean.contains("you've gifted") && clean.contains("we're sure they will love it!")) {
                log.info("Hive chat success: {}", msg);
                outputSuccess(CatalogManager.cleanFormatting(msg));
                this.giftStep = GiftStep.FINISHED;
                disconnectAndExit(0);
            }
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        log.info("Disconnected by server: {}", packet.getKickMessage());
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
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        log.info("Session disconnected: {}", reason);
        ticker.shutdown();
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

        // Schedule /gift command 1.5 seconds after spawning into the world
        ticker.schedule(() -> {
            log.info("Executing /gift command...");
            sendCommand("/gift");
        }, 1500, TimeUnit.MILLISECONDS);

        // Safety watchdog: auto-terminate after 25s if not already finished
        ticker.schedule(() -> {
            if (session.isConnected()) {
                log.warn("Bot execution watchdog reached (25s limit). Disconnecting...");
                disconnectAndExit(1);
            }
        }, 25, TimeUnit.SECONDS);
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
