/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.network.protocol;

import com.google.gson.JsonObject;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waterbucket.waterbucketpe.network.protocol.user.HandshakeUtils;
import dev.waterbucket.waterbucketpe.network.protocol.user.LoginData;
import dev.waterbucket.waterbucketpe.network.serverinfo.BedrockServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.text.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forwarding claims are what let a downstream server show the player's real address and
 * identity instead of the proxy's own dial address (127.0.0.1 on co-located hosts). These tests
 * pin down the built-in behavior: the client data the proxy parses stays exactly what the
 * client sent, the claims travel per dial (and can be dropped for backends that reject them),
 * and a strict backend's refusal is recognized by its exact signature.
 */
public class ForwardedClientIpTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("203.0.113.7", 51234);
    private static final InetSocketAddress CLIENT_V6 = new InetSocketAddress("2001:db8::1", 41940);

    private static LoginData loginData(SocketAddress address) {
        return LoginData.builder()
                .displayName("Steve")
                .uuid(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
                .address(address)
                .xuid("7654321")
                .minecraftId("MSA12345")
                .xboxAuthed(true)
                .keyPair(newKeyPair())
                .clientData(new JsonObject())
                .protocol(ProtocolVersion.MINECRAFT_PE_1_26_60)
                .shouldSendCertificateChain(false)
                .build();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(Curve.P_384.toECParameterSpec());
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet clientDataClaims(LoginPacket packet) throws ParseException {
        return SignedJWT.parse(packet.getClientJwt()).getJWTClaimsSet();
    }

    @Test
    public void parsedClientDataStaysExactlyWhatTheClientSent() throws Exception {
        // Forwarding claims are a property of each downstream dial, never of the parsed login:
        // baking them in here would send them to every backend, including the strict ones that
        // reject unknown keys as a bad packet and drop the whole login.
        JsonObject payload = new JsonObject();
        payload.addProperty("SkinData", "AWD=");
        payload.addProperty("DeviceOS", 1);
        SignedJWT clientJwt = HandshakeUtils.encodeJWT(newKeyPair(), payload);

        JsonObject parsed = HandshakeUtils.parseClientData(clientJwt);

        assertEquals(2, parsed.size());
        assertTrue(parsed.has("SkinData"));
        assertFalse(parsed.has("Waterdog_IP"));
        assertFalse(parsed.has("Waterdog_XUID"));
        assertFalse(parsed.has("Waterbucket_IP"));
    }

    @Test
    public void forwardingAddsTheEcosystemClaims() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, loginData(CLIENT));

        assertEquals("203.0.113.7", clientData.get("Waterdog_IP").getAsString());
        assertEquals("7654321", clientData.get("Waterdog_XUID").getAsString());
        assertEquals("MSA12345", clientData.get("Waterdog_MID").getAsString());
        assertTrue(clientData.get("Waterdog_Auth").getAsBoolean());
        // The fork's namespaced mirrors are gone: one claim set, the one every backend reads.
        assertFalse(clientData.has("Waterbucket_IP"));
        assertFalse(clientData.has("Waterbucket_XUID"));
        assertFalse(clientData.has("Waterbucket_Port"));
    }

    @Test
    public void ipv6AddressesAreForwardedVerbatim() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, loginData(CLIENT_V6));

        // getHostAddress() gives the canonical, uncompressed form
        assertEquals("2001:db8:0:0:0:0:0:1", clientData.get("Waterdog_IP").getAsString());
    }

    @Test
    public void unresolvedAddressesForwardTheHostTextInsteadOfThrowing() {
        // A session address without a resolved IP must not blow up the login path with an NPE;
        // the literal host text is forwarded so downstream still sees a useful value.
        JsonObject clientData = new JsonObject();
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("real-host.example", 51234);

        HandshakeUtils.addForwardingClaims(clientData, loginData(unresolved));

        assertEquals("real-host.example", clientData.get("Waterdog_IP").getAsString());
    }

    @Test
    public void foreignOrMissingAddressTypesSkipOnlyTheIpClaim() {
        // Whatever the transport hands over, claim injection must never throw into the login
        // catch-all (which would kick the player with a generic "Login failed").
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, loginData(null));
        assertFalse(clientData.has("Waterdog_IP"));
        assertEquals("7654321", clientData.get("Waterdog_XUID").getAsString());

        HandshakeUtils.addForwardingClaims(clientData, loginData(new java.net.SocketAddress() { }));
        assertFalse(clientData.has("Waterdog_IP"));
        assertTrue(clientData.has("Waterdog_XUID"));
    }

    @Test
    public void selfSignedPlayersStillForwardTheirAddress() {
        LoginData selfSigned = LoginData.builder()
                .displayName("Steve")
                .uuid(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
                .address(CLIENT)
                .xuid("")
                .minecraftId(null)
                .xboxAuthed(false)
                .keyPair(newKeyPair())
                .clientData(new JsonObject())
                .protocol(ProtocolVersion.MINECRAFT_PE_1_26_60)
                .shouldSendCertificateChain(false)
                .build();
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, selfSigned);

        assertEquals("203.0.113.7", clientData.get("Waterdog_IP").getAsString());
        // No Xbox identity to bind on the backend: those claims stay out instead of carrying junk.
        assertFalse(clientData.has("Waterdog_XUID"));
        assertFalse(clientData.has("Waterdog_MID"));
        assertFalse(clientData.has("Waterdog_Auth"));
    }

    @Test
    public void loginPacketCarriesTheClaimsWithoutTouchingTheParsedData() throws Exception {
        JsonObject clientData = new JsonObject();
        clientData.addProperty("SkinData", "AWD=");
        LoginData data = LoginData.builder()
                .displayName("Steve")
                .uuid(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
                .address(CLIENT)
                .xuid("7654321")
                .xboxAuthed(true)
                .keyPair(newKeyPair())
                .clientData(clientData)
                .protocol(ProtocolVersion.MINECRAFT_PE_1_26_60)
                .shouldSendCertificateChain(false)
                .build();

        LoginPacket packet = data.getLoginPacket(true);

        JWTClaimsSet claims = clientDataClaims(packet);
        assertEquals("203.0.113.7", claims.getStringClaim("Waterdog_IP"));
        assertEquals("7654321", claims.getStringClaim("Waterdog_XUID"));
        assertEquals(Boolean.TRUE, claims.getBooleanClaim("Waterdog_Auth"));
        // The client's own fields survive
        assertEquals("AWD=", claims.getStringClaim("SkinData"));
        // And the parsed login data was not mutated: a later rebuild starts from the client again
        assertFalse(clientData.has("Waterdog_IP"));
    }

    @Test
    public void cleanLoginPacketCarriesNoClaims() throws Exception {
        LoginData data = loginData(CLIENT);

        LoginPacket packet = data.getLoginPacket(false);

        JWTClaimsSet claims = clientDataClaims(packet);
        assertFalse(claims.getClaims().containsKey("Waterdog_IP"));
        assertFalse(claims.getClaims().containsKey("Waterdog_XUID"));
        assertFalse(claims.getClaims().containsKey("Waterdog_Auth"));
        assertFalse(claims.getClaims().containsKey("Waterdog_MID"));
    }

    @Test
    public void bothPacketVariantsAreCachedIndependently() throws Exception {
        LoginData data = loginData(CLIENT);
        LoginPacket withClaims = data.getLoginPacket(true);
        LoginPacket withoutClaims = data.getLoginPacket(false);

        assertTrue(clientDataClaims(withClaims).getClaims().containsKey("Waterdog_IP"));
        assertFalse(clientDataClaims(withoutClaims).getClaims().containsKey("Waterdog_IP"));
        // Cached, not rebuilt: a second request returns the very same packet instance
        assertNotEquals(withClaims, withoutClaims);
        assertEquals(withClaims, data.getLoginPacket(true));
        assertEquals(withoutClaims, data.getLoginPacket(false));
    }

    @Test
    public void serverForwardingStateGatesTheClaims() throws Exception {
        BedrockServerInfo server = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 19133), null);

        // Built-in: an unknown backend gets the claims until it proves otherwise
        assertEquals(ServerInfo.ForwardingSupport.UNKNOWN, server.getForwardingSupport());
        assertTrue(server.forwardClaims());

        server.setForwardingSupport(ServerInfo.ForwardingSupport.UNSUPPORTED);
        assertFalse(server.forwardClaims());

        server.setForwardingSupport(ServerInfo.ForwardingSupport.SUPPORTED);
        assertTrue(server.forwardClaims());
    }
}
