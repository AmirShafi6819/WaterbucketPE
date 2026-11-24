/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distribution under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.onlyproxy;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.serverinfo.BedrockServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.NetherNetServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfoMap;
import dev.waterbucket.waterbucketpe.utils.config.proxy.OnlyProxyJoinSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the Only Proxy Join rule plan without touching the host firewall: port collection
 * with dedupe, the idempotent check-then-apply plan (and the ACCEPT-before-DROP order on
 * Linux), the Windows netsh variants, and the status semantics.
 */
class OnlyProxyJoinManagerTest {

    private ProxyServer proxy;
    private OnlyProxyJoinSettings settings;
    private ServerInfoMap serverInfoMap;
    private OnlyProxyJoinManager manager;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        this.settings = new OnlyProxyJoinSettings();
        this.serverInfoMap = new ServerInfoMap();

        var config = this.proxyConfiguration();
        when(this.proxy.getConfiguration()).thenReturn(config);
        when(this.proxy.getServerInfoMap()).thenReturn(this.serverInfoMap);

        this.manager = new OnlyProxyJoinManager(this.proxy) {
            @Override
            boolean isWindows() {
                return false;
            }
        };
    }

    private dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig proxyConfiguration() {
        dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig config =
                mock(dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig.class);
        when(config.getOnlyProxyJoinSettings()).thenReturn(this.settings);
        return config;
    }

    @Test
    void portsAreCollectedSortedAndDeduplicated() {
        this.serverInfoMap.putIfAbsent("lobby1", new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30102), null));
        this.serverInfoMap.putIfAbsent("lobby2", new BedrockServerInfo("lobby2",
                new InetSocketAddress("127.0.0.1", 30100), null));
        // Two servers on the same port: one rule
        this.serverInfoMap.putIfAbsent("creative", new NetherNetServerInfo("creative",
                new InetSocketAddress("127.0.0.1", 30102), null));

        List<Integer> ports = this.manager.collectRules().stream().map(OnlyProxyJoinManager.PortRule::port).toList();
        assertEquals(List.of(30100, 30102), ports, "ports must be sorted and deduplicated");
    }

    @Test
    void linuxApplyPlanChecksThenAddsDropAndLoopbackAccept() {
        BedrockServerInfo server = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null);
        this.serverInfoMap.putIfAbsent("lobby1", server);

        OnlyProxyJoinManager.PortRule rule = this.manager.collectRules().get(0);
        List<List<String>> plan = this.manager.applyPlan(rule);

        // Both checks report missing (runQuiet default = real iptables call; in the sandbox
        // iptables is absent so the checks fail and the plan contains the adds)
        assertEquals(2, plan.size(), "missing rules must produce check+add pairs");
        List<String> first = plan.get(0);
        List<String> second = plan.get(1);
        assertEquals("ACCEPT", last(first), "the loopback accept must be applied first");
        assertEquals("DROP", last(second), "the global drop comes after the accept");
        assertTrue(String.join(" ", first).contains("waterbucket-opj"), "rules carry the marker comment");
        assertTrue(String.join(" ", second).contains("30101"), "the port is substituted");
    }

    @Test
    void linuxRemovePlanDeletesBothRules() {
        BedrockServerInfo server = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null);
        this.serverInfoMap.putIfAbsent("lobby1", server);

        OnlyProxyJoinManager.PortRule rule = this.manager.collectRules().get(0);
        List<List<String>> plan = this.manager.removePlan(rule);
        assertEquals(2, plan.size(), "both the accept and the drop must be removed");
        assertEquals("-D", plan.get(0).get(2), "removal uses exact-match deletes");
        assertEquals("-D", plan.get(1).get(2));
    }

    @Test
    void windowsPlanUsesNamedNetshRules() {
        this.manager = new OnlyProxyJoinManager(this.proxy) {
            @Override
            boolean isWindows() {
                return true;
            }
        };
        this.serverInfoMap.putIfAbsent("lobby1", new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null));

        OnlyProxyJoinManager.PortRule rule = this.manager.collectRules().get(0);
        List<List<String>> plan = this.manager.applyPlan(rule);
        assertEquals(1, plan.size(), "windows maintains one block rule per port");
        List<String> add = plan.get(0);
        String joined = String.join(" ", add);
        assertTrue(joined.contains("WaterbucketOPJ-30101"), "the rule name carries the port");
        assertTrue(joined.contains("action=block"), "the rule blocks direct access");
        assertTrue(joined.contains("protocol=udp"), "bedrock is udp-only");
        assertTrue(joined.contains("localport=30101"), "the port is substituted");

        List<List<String>> removal = this.manager.removePlan(rule);
        assertEquals(1, removal.size());
        assertTrue(String.join(" ", removal.get(0)).contains("delete"), "removal deletes the named rule");
    }

    @Test
    void statusReportsRulePresenceAndApplyIsIdempotent() {
        this.serverInfoMap.putIfAbsent("lobby1", new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null));
        OnlyProxyJoinManager.PortRule rule = this.manager.collectRules().get(0);

        // A manager whose checks say "everything already in place": empty plans, status true
        OnlyProxyJoinManager present = new OnlyProxyJoinManager(this.proxy) {
            @Override
            boolean isWindows() {
                return false;
            }

            @Override
            boolean runQuiet(List<String> command) {
                return true;
            }
        };
        assertTrue(present.applyPlan(rule).isEmpty(), "present rules need no work");
        Map<Integer, Boolean> status = present.status();
        assertEquals(Boolean.TRUE, status.get(30101), "present rules report protected");
        assertTrue(present.apply(), "apply on an already-protected host succeeds without changes");

        // A manager whose checks say "missing" applies the plan once and records success
        AtomicInteger executed = new AtomicInteger();
        OnlyProxyJoinManager missing = new OnlyProxyJoinManager(this.proxy) {
            @Override
            boolean isWindows() {
                return false;
            }

            @Override
            boolean runQuiet(List<String> command) {
                return false;
            }

            @Override
            boolean run(List<String> command, String context) {
                executed.incrementAndGet();
                return true;
            }
        };
        assertFalse(missing.status().get(30101), "missing rules report unprotected");
        assertTrue(missing.apply(), "applying the plan succeeds");
        assertEquals(2, executed.get(), "both the accept and the drop were applied");
        assertTrue(missing.isApplied(), "a successful apply leaves the manager applied");

        // manage_firewall=false: apply succeeds without running anything
        this.settings.manageFirewall(false);
        AtomicInteger untouched = new AtomicInteger();
        OnlyProxyJoinManager manual = new OnlyProxyJoinManager(this.proxy) {
            @Override
            boolean isWindows() {
                return false;
            }

            @Override
            boolean run(List<String> command, String context) {
                untouched.incrementAndGet();
                return true;
            }
        };
        assertTrue(manual.apply(), "manual mode is a no-op success");
        assertEquals(0, untouched.get(), "manual mode must not execute firewall commands");
        assertFalse(manual.isApplied(), "manual mode does not claim the applied state");
    }

    private static String last(List<String> command) {
        return command.get(command.size() - 1);
    }
}
