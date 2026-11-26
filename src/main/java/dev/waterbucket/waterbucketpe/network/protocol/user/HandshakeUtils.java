/*
 * Copyright 2022 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.network.protocol.user;

import com.google.gson.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Various utilities for parsing Handshake data
 */
@Log4j2
public class HandshakeUtils {

    @Getter
    private static final KeyPair privateKeyPair;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(Curve.P_384.toECParameterSpec());
            privateKeyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Unable to generate private keyPair!", e);
        }
    }

    // Certificate chain
    public static JsonObject createChainExtraData(String displayName, String xuid, UUID uuid) {
        JsonObject extraData = new JsonObject();
        extraData.addProperty("displayName", displayName);
        extraData.addProperty("XUID", xuid);
        extraData.addProperty("identity", uuid.toString());
        return extraData;
    }

    public static SignedJWT createClientDataChain(KeyPair pair, JsonObject extraData) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("nbf", timestamp - 3600);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("iss", "self");
        dataChain.addProperty("certificateAuthority", true);
        dataChain.add("extraData", extraData);
        dataChain.addProperty("randomNonce", UUID.randomUUID().getLeastSignificantBits());
        dataChain.addProperty("identityPublicKey", publicKeyBase64);
        return encodeJWT(pair, dataChain);
    }

    // Token
    public static SignedJWT createClientDataToken(KeyPair pair, String displayName, String xuid, UUID uuid, String minecraftId) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("cpk", publicKeyBase64);
        dataChain.addProperty("leguuid", uuid.toString());
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("xname", displayName);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("mid", minecraftId);
        dataChain.addProperty("ap", 7);
        dataChain.addProperty("iss", "self");
        // Some downstream server software seems to require this, but the protocol library does not.
        dataChain.addProperty("aud", "api://auth-minecraft-services/multiplayer");
        // Normally "xid" is sent as empty string for self-signed certificates, but we include it anyway
        dataChain.addProperty("xid", xuid);
        return encodeJWT(pair, dataChain);
    }

    public static SignedJWT encodeJWT(KeyPair pair, JsonObject payload) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        URI x5u = URI.create(publicKeyBase64);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES384).x509CertURL(x5u).build();
        try {
            SignedJWT jwt = new SignedJWT(header, JWTClaimsSet.parse(payload.toString()));
            signJwt(jwt, (ECPrivateKey) pair.getPrivate());
            return jwt;
        } catch (JOSEException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static void signJwt(JWSObject jws, ECPrivateKey key) throws JOSEException {
        jws.sign(new ECDSASigner(key, Curve.P_384));
    }

    public static boolean verifyJwt(JWSObject jws, ECPublicKey key) throws JOSEException {
        return jws.verify(new ECDSAVerifier(key));
    }

    public static HandshakeEntry processHandshake(BedrockSession session, LoginPacket packet, ProtocolVersion protocol, boolean strict) throws Exception {
        ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
        boolean xboxAuth = result.signed();
        ChainValidationResult.IdentityClaims identityClaims = result.identityClaims();
        ChainValidationResult.IdentityData identityData = identityClaims.extraData;
        ECPublicKey identityPublicKey = (ECPublicKey) identityClaims.parsedIdentityPublicKey();
        String xuid = identityData.xuid;
        UUID uuid = identityData.identity;
        String minecraftId = identityData.minecraftId;

        SignedJWT clientDataJwt = SignedJWT.parse(packet.getClientJwt());
        JsonObject clientData = HandshakeUtils.parseClientData(clientDataJwt);
        if (!verifyJwt(clientDataJwt, identityPublicKey) && strict) {
            xboxAuth = false;
        }
        String displayName;
        if (ProxyServer.getInstance().getConfiguration().isReplaceUsernameSpaces()) {
            // Literal replacement, no regex engine needed on the login path
            displayName = identityData.displayName.replace(' ', '_');
        } else {
            displayName = identityData.displayName;
        }

        if (xboxAuth) {
            ProxyServer.getInstance().getLogger().debug("Xbox live authenticated: {}", identityData.displayName);
        }
        // Before 1.26.20, client sends CertificateChainPayload in LoginPacket instead of TokenPayload
        // We are trying to replicate that behavior.
        return new HandshakeEntry(identityPublicKey, clientData, xuid, uuid, displayName, minecraftId, xboxAuth, protocol,
                packet.getAuthPayload() instanceof CertificateChainPayload ||
                    protocol.isBefore(ProtocolVersion.MINECRAFT_PE_1_26_20));
    }

    public static JsonObject parseClientData(JWSObject clientJwt) {
        // The parsed object is exactly what the client sent. Forwarding claims are a property of
        // each downstream dial (see LoginData), never of the login itself: baking them in here
        // would send them to every backend including the strict ones that reject unknown keys.
        return (JsonObject) JsonParser.parseString(clientJwt.getPayload().toString());
    }

    /**
     * Claims every downstream server can read to learn who is really joining. The address a
     * server sees on its socket is the proxy's own dial address (loopback on co-located hosts),
     * so the real client address and the Xbox identity travel inside the client data JWT
     * instead. {@code Waterdog_*} is the de-facto field set the whole Bedrock proxy ecosystem
     * reads (Nukkit-MOT, Geyser, and the PocketMine-MP companion plugin shipped under
     * {@code companion/pocketmine/}); the proxy adds it on every dial because forwarding is a
     * built-in property of the proxy, not a config switch.
     * <p>
     * Strict server software that rejects unknown client data keys fails the login, so the
     * first join to such a backend is retried without the claims and the server is remembered
     * (see {@link ProxiedPlayer#onDownstreamFailure}); installing the companion plugin turns
     * the full forwarding back on for it.
     */
    public static void addForwardingClaims(JsonObject clientData, LoginData loginData) {
        // Never let forwarding break the login: an unexpected address type (or an unresolved
        // one) simply skips the IP claim instead of throwing into the login catch-all, which
        // would disconnect the player with a generic "Login failed".
        if (loginData.getAddress() instanceof InetSocketAddress inet) {
            // getHostAddress() is null for an unresolved address; getHostString() then returns
            // the literal host text without a reverse DNS lookup, so forwarding still carries something.
            String ip = inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
            clientData.addProperty("Waterdog_IP", ip);
        }
        String xuid = loginData.getXuid();
        if (xuid != null && !xuid.isEmpty()) {
            clientData.addProperty("Waterdog_XUID", xuid);
        }
        if (loginData.isXboxAuthed()) {
            clientData.addProperty("Waterdog_Auth", true);
        }
        String minecraftId = loginData.getMinecraftId();
        if (minecraftId != null && !minecraftId.isEmpty()) {
            clientData.addProperty("Waterdog_MID", minecraftId);
        }
    }

    public static void processEncryption(BedrockSession session, PublicKey key) throws Exception {
        byte[] token = EncryptionUtils.generateRandomToken();
        SecretKey encryptionKey = EncryptionUtils.getSecretKey(privateKeyPair.getPrivate(), key, token);

        ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
        packet.setJwt(EncryptionUtils.createHandshakeJwt(privateKeyPair, token));

        session.getPeer().getChannel().eventLoop().execute(() -> {
            session.sendPacketImmediately(packet);
            session.enableEncryption(encryptionKey);
        });
    }

}
