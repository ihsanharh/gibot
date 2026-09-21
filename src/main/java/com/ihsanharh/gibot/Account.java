package com.ihsanharh.gibot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.util.MinecraftAuth4To5Migrator;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Log4j2
@Getter
public class Account {
    private final BedrockAuthManager authManager;

    public Account(BedrockAuthManager authManager) {
        this.authManager = authManager;
    }

    /**
     * Loads existing authenticated account from auth.json, or initiates Microsoft Device Code login.
     */
    public static Account getOrAuthenticate(HttpClient httpClient, String gameVersion) throws Exception {
        Path authPath = Paths.get("auth.json");

        // Try to load and refresh saved account
        if (Files.exists(authPath) && Files.isRegularFile(authPath)) {
            try {
                String accountString = Files.readString(authPath, StandardCharsets.UTF_8);
                JsonObject accountJson = JsonParser.parseString(accountString).getAsJsonObject();
                if (accountJson.has("mcChain")) {
                    accountJson = MinecraftAuth4To5Migrator.migrateBedrockSave(accountJson);
                }
                BedrockAuthManager manager = BedrockAuthManager.fromJson(httpClient, gameVersion, accountJson);
                Account account = new Account(manager);
                account.refresh();
                save(authPath, account);
                log.info("Successfully loaded account: {}", account.getDisplayName());
                return account;
            } catch (Exception e) {
                log.warn("Failed to refresh saved account ({}): {}. Initiating new login...", authPath, e.getMessage());
            }
        }

        // Fresh login via Microsoft OAuth Device Code flow
        log.info("Starting Microsoft Account device code login...");
        BedrockAuthManager.Builder builder = BedrockAuthManager.create(httpClient, gameVersion);
        BedrockAuthManager manager = builder.login(DeviceCodeMsaAuthService::new, new java.util.function.Consumer<MsaDeviceCode>() {
            @Override
            public void accept(MsaDeviceCode msaDeviceCode) {
                String verificationUri = msaDeviceCode.getVerificationUri();
                String queryParam = verificationUri.contains("?") ? "&" : "?";
                String fullUrl = verificationUri + queryParam + "otc=" + msaDeviceCode.getUserCode();

                System.out.println("Microsoft Account Login Required!");
                System.out.println("1. Visit URL: " + fullUrl);
                System.out.println("2. Or go to " + verificationUri + " and enter code: " + msaDeviceCode.getUserCode());

                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try {
                        Desktop.getDesktop().browse(URI.create(fullUrl));
                    } catch (IOException ignored) {
                    }
                }
            }
        });

        Account account = new Account(manager);
        account.refresh();
        save(authPath, account);
        log.info("Login complete! Account saved to {}", authPath);
        return account;
    }

    public String getDisplayName() {
        try {
            return authManager.getMinecraftMultiplayerToken().getCached().getDisplayName();
        } catch (Exception e) {
            return "BedrockBot";
        }
    }

    public void refresh() throws Exception {
        authManager.getMinecraftSession().refresh();
        authManager.getMinecraftCertificateChain().refresh();
        authManager.getMinecraftMultiplayerToken().refresh();
        authManager.getXboxLiveXstsToken().refresh();
    }

    private static void save(Path path, Account account) throws IOException {
        JsonObject json = BedrockAuthManager.toJson(account.getAuthManager());
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
