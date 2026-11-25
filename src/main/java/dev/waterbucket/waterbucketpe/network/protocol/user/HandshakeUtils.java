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
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
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
import java.net.SocketAddress;
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
        JsonObject clientData = HandshakeUtils.parseClientData(clientDataJwt, xuid, session.getSocketAddress());
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
            ProxyConfig config = ProxyServer.getInstance().getConfiguration();
            if (config.useLoginExtras()) {
                // Waterdog_* keys are kept so downstream servers migrated from WaterdogPE keep working;
                // Waterbucket_* keys mirror them under the fork's own namespace.
                clientData.addProperty("Waterdog_Auth", true);
                clientData.addProperty("Waterbucket_Auth", true);
                clientData.addProperty("Waterdog_XUID", identityData.xuid);
                clientData.addProperty("Waterbucket_XUID", identityData.xuid);
                if (identityData.minecraftId != null) {
                    clientData.addProperty("Waterdog_MID", identityData.minecraftId);
                    clientData.addProperty("Waterbucket_MID", identityData.minecraftId);
                }
            }
        }
        // Before 1.26.20, client sends CertificateChainPayload in LoginPacket instead of TokenPayload
        // We are trying to replicate that behavior.
        return new HandshakeEntry(identityPublicKey, clientData, xuid, uuid, displayName, minecraftId, xboxAuth, protocol,
                packet.getAuthPayload() instanceof CertificateChainPayload ||
                    protocol.isBefore(ProtocolVersion.MINECRAFT_PE_1_26_20));
    }

    public static JsonObject parseClientData(JWSObject clientJwt, String xuid, SocketAddress address) {
        JsonObject clientData = (JsonObject) JsonParser.parseString(clientJwt.getPayload().toString());
        ProxyConfig config = ProxyServer.getInstance().getConfiguration();
        addForwardingClaims(clientData, address, config.forwardClientIp(), config.useLoginExtras());
        addLoginExtras(clientData, xuid, config.useLoginExtras());
        return clientData;
    }

    /**
     * Claims every downstream server can read to learn where the player really connects from.
     * The address a server sees on its socket is the proxy's own dial address (loopback on
     * co-located hosts), so the real client address travels inside the client data JWT instead.
     * Waterdog_IP is the de-facto field the ecosystem reads - modern Bedrock server software
     * and the proxy login-extras plugins all check it; the Waterbucket_*
     * fields mirror it under the fork's own namespace and add the client's source port.
     * <p>
     * The legacy use_login_extras switch keeps its old meaning and still implies this, so
     * setups that relied on it keep forwarding the address.
     */
    public static void addForwardingClaims(JsonObject clientData, SocketAddress address, boolean forwardClientIp, boolean useLoginExtras) {
        if (!forwardClientIp && !useLoginExtras) {
            return;
        }
        // Never let forwarding break the login: an unexpected address type (or an unresolved
        // one) simply skips the claims instead of throwing into the login catch-all, which
        // would disconnect the player with a generic "Login failed".
        if (!(address instanceof InetSocketAddress)) {
            return;
        }
        InetSocketAddress inet = (InetSocketAddress) address;
        // getHostAddress() is null for an unresolved address; getHostString() then returns the
        // literal host text without a reverse DNS lookup, so forwarding still carries something.
        String ip = inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
        clientData.addProperty("Waterdog_IP", ip);
        clientData.addProperty("Waterbucket_IP", ip);
        clientData.addProperty("Waterbucket_Port", inet.getPort());
    }

    /**
     * The identity fields behind the legacy use_login_extras switch: the real XUID plus the
     * fork's namespaced copy, so downstream servers can bind accounts to the true owner.
     */
    public static void addLoginExtras(JsonObject clientData, String xuid, boolean useLoginExtras) {
        if (!useLoginExtras) {
            return;
        }
        // Waterdog_* keys kept for downstream compatibility, Waterbucket_* added for the fork
        clientData.addProperty("Waterdog_XUID", xuid);
        clientData.addProperty("Waterbucket_XUID", xuid);
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
