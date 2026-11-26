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

import com.google.gson.JsonObject;
import com.nimbusds.jwt.SignedJWT;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;

import java.net.SocketAddress;
import java.security.KeyPair;
import java.util.Collections;
import java.util.UUID;

/**
 * Holds relevant information passed to the proxy on the first connection (initial) in the LoginPacket.
 */
@Slf4j
@Builder
@Getter
public class LoginData {

    private final String displayName;
    private final UUID uuid;
    private final String xuid;
    private final String minecraftId;
    private final boolean xboxAuthed;
    private final SocketAddress address;
    private final ProtocolVersion protocol;
    /**
     * The host and port the client joined through, as
     * {@link org.cloudburstmc.protocol.bedrock.util.JoinAddress} reads its ServerAddress.
     */
    private final String joinHostname;
    private final int joinPort;

    @Builder.Default
    private final Platform devicePlatform = Platform.UNKNOWN;
    @Builder.Default
    private final String deviceModel = null;
    @Builder.Default
    private final String deviceId = null;

    private final KeyPair keyPair;
    private final JsonObject clientData;
    // Values rather than packets: which claims travel depends on the target server (see
    // getLoginPacket), so both variants are cached and a rebuild invalidates them together.
    private LoginPacket loginPacketWithClaims;
    private LoginPacket loginPacketWithoutClaims;

    // Values rather than packets, so every send builds its own.
    // Set on the upstream loop (RequestChunkRadiusPacket, ClientCacheStatusPacket) and read
    // on downstream loops when the login is replayed to a server, so writes must be visible.
    @Setter
    @Builder.Default
    private volatile int chunkRadius = 8;
    @Setter
    private volatile int maxChunkRadius;
    @Setter
    private volatile boolean cacheSupported;

    private final boolean shouldSendCertificateChain;

    /**
     * The credentials as the client sent them. Kept so they can be forwarded verbatim to a
     * downstream that authenticates players itself, which is only possible where the transport
     * carries no Bedrock encryption.
     */

    /**
     * Used to construct a new login packet using this.clientData and this.extraData signed by
     * this.keyPair. This method should be called every time client data is changed. Otherwise
     * the player will join downstream using old data.
     *
     * @return new LoginPacket.
     */
    public synchronized LoginPacket rebuildLoginPacket() {
        this.loginPacketWithClaims = null;
        this.loginPacketWithoutClaims = null;
        return this.getLoginPacket();
    }

    /**
     * The login to send to the given downstream server, signed by the proxy. The forwarding
     * claims travel when the server has not proven that it rejects unknown client data keys
     * (see {@link ServerInfo#forwardClaims()}), because real-IP forwarding is a built-in
     * property of the proxy and only a backend that cannot parse them turns it off.
     * <p>
     * Built once per claim variant, lazily, potentially from several downstream loops
     * (parallel dials). The synchronized block makes both the build and its publication safe;
     * the fields are only read after the method, never hot.
     *
     * @param target the server the login is being dialed for, or null for the claim-carrying default
     */
    public synchronized LoginPacket getLoginPacket(ServerInfo target) {
        return this.getLoginPacket(target == null || target.forwardClaims());
    }

    /**
     * The claim-carrying default variant, as plugins reading the login packet expect.
     */
    public synchronized LoginPacket getLoginPacket() {
        return this.getLoginPacket(true);
    }

    public synchronized LoginPacket getLoginPacket(boolean forwardingClaims) {
        LoginPacket cached = forwardingClaims ? this.loginPacketWithClaims : this.loginPacketWithoutClaims;
        if (cached != null) {
            return cached;
        }
        LoginPacket built = this.buildLoginPacket(forwardingClaims);
        if (forwardingClaims) {
            this.loginPacketWithClaims = built;
        } else {
            this.loginPacketWithoutClaims = built;
        }
        return built;
    }

    private LoginPacket buildLoginPacket(boolean forwardingClaims) {
        LoginPacket loginPacket = new LoginPacket();
        // A copy, so the claims never leak into this.clientData, which the upstream skin
        // parsing and any plugin edits keep working on.
        JsonObject payload = forwardingClaims ? this.clientData.deepCopy() : this.clientData;
        if (forwardingClaims) {
            HandshakeUtils.addForwardingClaims(payload, this);
        }
        SignedJWT signedClientData = HandshakeUtils.encodeJWT(this.keyPair, payload);
        loginPacket.setClientJwt(signedClientData.serialize());
        loginPacket.setProtocolVersion(this.protocol.getProtocol());
        if (shouldSendCertificateChain) {
            JsonObject extraData = HandshakeUtils.createChainExtraData(displayName, xuid, uuid);
            SignedJWT signedPayload = HandshakeUtils.createClientDataChain(this.keyPair, extraData);
            loginPacket.setAuthPayload(new CertificateChainPayload(Collections.singletonList(signedPayload.serialize()), AuthType.SELF_SIGNED));
        } else {
            SignedJWT signedPayload = HandshakeUtils.createClientDataToken(this.keyPair, displayName, xuid, uuid, minecraftId);
            loginPacket.setAuthPayload(new TokenPayload(signedPayload.serialize(), AuthType.SELF_SIGNED));
        }
        return loginPacket;
    }

}
