/*
 * Copyright 2026 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.protocol;

import com.google.gson.JsonObject;
import dev.waterbucket.waterbucketpe.network.protocol.user.HandshakeUtils;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ConfigMigrations;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forwarding claims are what let a downstream server show the player's real address instead
 * of the proxy's own dial address (127.0.0.1 on co-located hosts). These tests pin down which
 * switch adds which claim, so neither the default nor the legacy use_login_extras behavior
 * silently changes shape.
 */
public class ForwardedClientIpTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("203.0.113.7", 51234);
    private static final InetSocketAddress CLIENT_V6 = new InetSocketAddress("2001:db8::1", 41940);

    @TempDir
    Path tempDir;

    private ProxyConfig config(String yaml) throws Exception {
        File file = tempDir.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), yaml);
        ProxyConfig config = new ProxyConfig(file);
        config.init();
        return config;
    }

    @Test
    public void forwardingIsOffForConfigsThatPredateTheKey() throws Exception {
        // A config.yml without forward_client_ip (fresh or upgraded from an older release) gets
        // the field default: off. The forwarded fields are extra keys in the client data object,
        // and strict server software such as PocketMine-MP rejects unknown keys as a bad packet,
        // so a default-on switch must never come back.
        assertFalse(config("").forwardClientIp());
    }

    @Test
    public void forwardingCanBeTurnedOnExplicitly() throws Exception {
        // On the current layout an operator's explicit switch binds as-is; only the upgrade
        // step resets the shipped-on 1.5.x/1.6.0 value, so the file carries its version.
        assertTrue(config("config_version: " + ConfigMigrations.CURRENT + "\nforward_client_ip: true\n").forwardClientIp());
    }

    @Test
    public void forwardingCanBeTurnedOff() throws Exception {
        assertFalse(config("forward_client_ip: false\n").forwardClientIp());
    }

    @Test
    public void forwardingAddsTheStandardAndNamespacedClaims() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, CLIENT, true, false);

        assertEquals("203.0.113.7", clientData.get("Waterdog_IP").getAsString());
        assertEquals("203.0.113.7", clientData.get("Waterbucket_IP").getAsString());
        assertEquals(51234, clientData.get("Waterbucket_Port").getAsInt());
    }

    @Test
    public void ipv6AddressesAreForwardedVerbatim() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, CLIENT_V6, true, false);

        // getHostAddress() gives the canonical, uncompressed form
        assertEquals("2001:db8:0:0:0:0:0:1", clientData.get("Waterdog_IP").getAsString());
        assertEquals(41940, clientData.get("Waterbucket_Port").getAsInt());
    }

    @Test
    public void bothSwitchesOffForwardNothing() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, CLIENT, false, false);

        assertFalse(clientData.has("Waterdog_IP"));
        assertFalse(clientData.has("Waterbucket_IP"));
        assertFalse(clientData.has("Waterbucket_Port"));
    }

    @Test
    public void legacyUseLoginExtrasStillImpliesForwarding() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, CLIENT, false, true);

        assertTrue(clientData.has("Waterdog_IP"));
        assertTrue(clientData.has("Waterbucket_IP"));
    }

    @Test
    public void loginExtrasCarryTheRealXuid() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addLoginExtras(clientData, "7654321", true);

        assertEquals("7654321", clientData.get("Waterdog_XUID").getAsString());
        assertEquals("7654321", clientData.get("Waterbucket_XUID").getAsString());
    }

    @Test
    public void loginExtrasOffAddsNoIdentity() {
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addLoginExtras(clientData, "7654321", false);

        assertFalse(clientData.has("Waterdog_XUID"));
        assertFalse(clientData.has("Waterbucket_XUID"));
    }

    @Test
    public void unresolvedAddressesForwardTheHostTextInsteadOfThrowing() {
        // A session address without a resolved IP must not blow up the login path with an NPE;
        // the literal host text is forwarded so downstream still sees a useful value.
        JsonObject clientData = new JsonObject();
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("real-host.example", 51234);

        HandshakeUtils.addForwardingClaims(clientData, unresolved, true, false);

        assertEquals("real-host.example", clientData.get("Waterdog_IP").getAsString());
        assertEquals("real-host.example", clientData.get("Waterbucket_IP").getAsString());
        assertEquals(51234, clientData.get("Waterbucket_Port").getAsInt());
    }

    @Test
    public void foreignOrMissingAddressTypesForwardNothingInsteadOfThrowing() {
        // Whatever the transport hands over, claim injection must never throw into the login
        // catch-all (which would kick the player with a generic "Login failed").
        JsonObject clientData = new JsonObject();

        HandshakeUtils.addForwardingClaims(clientData, null, true, false);
        assertFalse(clientData.has("Waterdog_IP"));

        HandshakeUtils.addForwardingClaims(clientData, new java.net.SocketAddress() { }, true, false);
        assertFalse(clientData.has("Waterdog_IP"));
        assertFalse(clientData.has("Waterbucket_Port"));
    }
}
