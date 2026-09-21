package com.ihsanharh.gibot;

import lombok.experimental.UtilityClass;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.DualPayload;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

@UtilityClass
public class ForgeryUtils {
    public static AuthPayload forgeOnlineAuthData(BedrockAuthManager authManager) {
        return new DualPayload(List.of(".."), authManager.getMinecraftMultiplayerToken().getCached().getToken(), AuthType.FULL);
    }

    @SuppressWarnings("unchecked")
    public static String forgeOnlineSkinData(Account account, JSONObject skinData, SocketAddress serverAddress) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(account.getAuthManager().getSessionKeyPair().getPublic().getEncoded());

        HashMap<String, Object> overrideData = new HashMap<>();
        overrideData.put("DeviceId", account.getAuthManager().getDeviceId().toString().replace("-", ""));
        overrideData.put("ClientRandomId", java.util.concurrent.ThreadLocalRandom.current().nextLong());
        overrideData.put("ThirdPartyName", account.getAuthManager().getMinecraftMultiplayerToken().getCached().getDisplayName());

        if (serverAddress instanceof InetSocketAddress a) {
            overrideData.put("ServerAddress", a.getHostString() + ":" + a.getPort());
        }

        skinData.putAll(overrideData);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue("ES384");
        jws.setHeader(HeaderParameterNames.X509_URL, publicKeyBase64);
        jws.setPayload(skinData.toJSONString());
        jws.setKey(account.getAuthManager().getSessionKeyPair().getPrivate());

        try {
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new RuntimeException("Failed to serialize skin JWT", e);
        }
    }
}
