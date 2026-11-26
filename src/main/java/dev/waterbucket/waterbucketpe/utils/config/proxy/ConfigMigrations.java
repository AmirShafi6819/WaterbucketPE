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

package dev.waterbucket.waterbucketpe.utils.config.proxy;

import lombok.extern.log4j.Log4j2;
import net.cubespace.Yamler.Config.ConfigSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Brings a config.yml written in an older layout up to date, on the raw file before any option is
 * read. The layout version is the number of steps applied, kept in config_version.
 * <p>
 * A step is needed when a pushed layout renames, moves or removes an option. Adding an option needs
 * none, since the loader writes new options back by itself and an older proxy still reads the file.
 * Changed defaults are left alone, as the file holds every option and an old default cannot be told
 * apart from a choice. Step 4 breaks that rule once on purpose: the shipped-on forwarding default
 * rejected logins on strict server software outright, which is worse than resetting a value that
 * could not have been relied on unless it was set deliberately - the warning log names the way back.
 * Step 5 removes a whole section, for a feature that no longer exists in any form.
 * <p>
 * Note {@link ConfigSection#get} and {@link ConfigSection#set} create every section on the way, so a
 * path is checked with {@link ConfigSection#has} first.
 */
@Log4j2
public final class ConfigMigrations {

    private static final List<Consumer<ConfigSection>> STEPS = List.of(
            ConfigMigrations::netherNetLayout,
            ConfigMigrations::proxyProtocolWhitelist,
            ConfigMigrations::proxyProtocolSwitch,
            ConfigMigrations::forwardingBuiltIn,
            ConfigMigrations::removeSleepMode,
            ConfigMigrations::removeOnlyProxyJoin
    );

    /**
     * The layout version this build reads and writes, kept in config_version.
     */
    public static final int CURRENT = STEPS.size();

    private ConfigMigrations() {
    }

    static void migrate(ConfigSection root, int version) {
        for (int step = version; step < CURRENT; step++) {
            STEPS.get(step).accept(root);
        }
    }

    /**
     * Step 1: the NetherNet options take Geyser's names and its signaling block, and
     * nethernet.enabled becomes listener.transport.
     */
    private static void netherNetLayout(ConfigSection root) {
        String enabled = "nethernet.enabled";
        if (root.has(enabled)) {
            // RakNet always ran, with NetherNet next to it or not
            if (!root.has("listener.transport")) {
                root.set("listener.transport", isTrue(root, enabled) ? "both" : "raknet");
            }
            root.remove(enabled);
        }
        // Merged first, as the shared list then moves into the signaling block
        shareNetherNetAddresses(root);
        move(root, "nethernet.udp_port", "nethernet.webrtc_port");
        move(root, "nethernet.signaling_mode", "nethernet.signaling.mode");
        for (String key : List.of("advertise_addresses", "stun_discovery", "trusted_proxies", "proxy_protocol", "nxs")) {
            move(root, "nethernet." + key, "nethernet.signaling." + key);
        }
        move(root, "nethernet.https", "nethernet.signaling.builtin.https");
    }

    /**
     * Step 2: nethernet.signaling.trusted_proxies is X-Forwarded-For only, and PROXY headers are read from
     * network_settings.proxy_protocol_whitelisted_ips, as in Geyser. With proxy_protocol on, the trusted
     * proxies were read for both, so they go in both lists, but only while RakNet's switch is on: since
     * step 3, which always follows, a listed proxy turns it on. Its warnings describe the layout after step 3.
     */
    private static void proxyProtocolWhitelist(ConfigSection root) {
        String switchPath = "nethernet.signaling.proxy_protocol";
        if (!root.has(switchPath)) {
            return;
        }
        boolean proxyProtocol = isTrue(root, switchPath);
        root.remove(switchPath);
        if (!proxyProtocol) {
            return;
        }

        String enabledPath = "network_settings.enable_proxy_protocol";
        if (!root.has(enabledPath) || !isTrue(root, enabledPath)) {
            log.warn("nethernet.signaling.proxy_protocol is gone, so signaling connections that carry a PROXY header "
                    + "fail until their proxies are listed in network_settings.proxy_protocol_whitelisted_ips, "
                    + "which makes RakNet require PROXY headers from them too");
            return;
        }
        String whitelistPath = "network_settings.proxy_protocol_whitelisted_ips";
        List<String> whitelist = entries(root, whitelistPath, "nethernet.signaling.trusted_proxies");
        root.set(whitelistPath, whitelist);
        if (!whitelist.isEmpty()) {
            log.warn("RakNet now only takes connections from network_settings.proxy_protocol_whitelisted_ips, which "
                    + "this update filled from nethernet.signaling.trusted_proxies: add RakNet's proxies if they differ");
        }
    }

    /**
     * Step 3: listing proxies in network_settings.proxy_protocol_whitelisted_ips turns PROXY protocol on, as in
     * Geyser, so enable_proxy_protocol goes. The list no longer covers webrtc_port, whose relays get
     * network_settings.webrtc_proxy_protocol_relays, filled from it, as it was read for both.
     */
    private static void proxyProtocolSwitch(ConfigSection root) {
        String switchPath = "network_settings.enable_proxy_protocol";
        if (!root.has(switchPath)) {
            return;
        }
        boolean enabled = isTrue(root, switchPath);
        root.remove(switchPath);

        String listPath = "network_settings.proxy_protocol_whitelisted_ips";
        if (!enabled) {
            // Ignored while the switch was off, and would turn it on now
            if (root.has(listPath)) {
                root.set(listPath, new ArrayList<>());
            }
            return;
        }
        List<String> proxies = entries(root, listPath);
        if (proxies.isEmpty()) {
            log.warn("network_settings.enable_proxy_protocol is gone: listing proxies in "
                    + "network_settings.proxy_protocol_whitelisted_ips now turns PROXY protocol on. Yours was on with an "
                    + "empty list, which took a PROXY header from any address on RakNet, so it is off until you list "
                    + "your proxies there");
            return;
        }
        String relaysPath = "network_settings.webrtc_proxy_protocol_relays";
        if (!root.has(relaysPath)) {
            root.set(relaysPath, proxies);
            log.warn("NetherNet gameplay traffic only comes through network_settings.webrtc_proxy_protocol_relays, which "
                    + "this update filled from network_settings.proxy_protocol_whitelisted_ips: forward webrtc_port "
                    + "through them with PROXY v2 headers and name them in nethernet.signaling.advertise_addresses, or "
                    + "empty it if players reach webrtc_port directly");
        }
    }

    /**
     * Step 4: the forwarding switches are gone. Real-IP forwarding is built into the proxy now:
     * every dial carries the forwarding claims, and a backend that cannot parse them (strict
     * server software such as PocketMine-MP) is remembered and later joins skip the claims. The
     * two switches {@code use_login_extras} and {@code forward_client_ip} leave every file -
     * their only two effects were forwarding claims that can no longer be configured away, and
     * the 1.5.6/1.6.0 default-on {@code forward_client_ip} that blocked every login to a strict
     * backend with a "JSON property" bad packet, because a carried-over true kept injecting
     * claims no matter what the operator changed afterwards.
     */
    private static void forwardingBuiltIn(ConfigSection root) {
        for (String switchPath : List.of("use_login_extras", "forward_client_ip")) {
            if (root.has(switchPath)) {
                root.remove(switchPath);
            }
        }
        log.warn("forward_client_ip and use_login_extras are gone: real-IP forwarding is now built in. Strict backends "
                + "such as PocketMine-MP that rejected the forwarded login fields as a bad packet get their joins retried "
                + "without the claims automatically; run the WaterbucketPE companion plugin (companion/pocketmine) on them "
                + "to get the real IP and identity there too");
    }

    /**
     * Step 5: the sleep mode feature is gone. It paused two probes that only run behind niche
     * options and forced a garbage collection on an idle proxy, which saves nothing the JVM does
     * not save on its own - so the section leaves every file that still carries it.
     */
    private static void removeSleepMode(ConfigSection root) {
        if (root.has("sleep_mode")) {
            root.remove("sleep_mode");
        }
    }

    /**
     * Step 6: the firewall-based only_proxy_join section is gone. Asking the proxy to write
     * iptables/netsh rules for the backend ports was fragile and never as reliable as the
     * backend deciding on its own: the forwarding claims now always travel in the login, and
     * the companion plugin shipped under companion/pocketmine refuses any join that did not
     * come from the proxy. The section leaves every file that still carries it.
     */
    private static void removeOnlyProxyJoin(ConfigSection root) {
        if (root.has("only_proxy_join")) {
            root.remove("only_proxy_join");
            log.warn("only_proxy_join is gone: the firewall-based rules it kept are no longer written. The protocol-level "
                    + "replacement runs on the backend itself - install the WaterbucketPE companion plugin "
                    + "(companion/pocketmine) there, and with require-proxy on it refuses every join that bypassed the proxy");
        }
    }

    /**
     * Built-in signaling and NXS share one advertise_addresses list, each address once, and the NXS
     * stun_discovery switch moves next to it.
     */
    private static void shareNetherNetAddresses(ConfigSection root) {
        String nxsAddresses = "nethernet.nxs.advertise_addresses";
        if (root.has(nxsAddresses)) {
            root.set("nethernet.advertise_addresses", entries(root, "nethernet.advertise_addresses", nxsAddresses));
            root.remove(nxsAddresses);
        }

        String nxsStunDiscovery = "nethernet.nxs.advanced.stun_discovery";
        if (root.has(nxsStunDiscovery)) {
            if (!root.has("nethernet.stun_discovery")) {
                root.set("nethernet.stun_discovery", root.get(nxsStunDiscovery));
            }
            root.remove(nxsStunDiscovery);
        }
    }

    /**
     * The entries of the lists at these paths, trimmed, each once, in order.
     */
    private static List<String> entries(ConfigSection root, String... paths) {
        Set<String> entries = new LinkedHashSet<>();
        for (String path : paths) {
            if (root.has(path) && root.get(path) instanceof List<?> list) {
                list.forEach(entry -> entries.add(String.valueOf(entry).trim()));
            }
        }
        entries.remove("");
        return new ArrayList<>(entries);
    }

    private static boolean isTrue(ConfigSection root, String path) {
        Object value = root.get(path);
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Moves an option, keeping the one already at the new path if the file has both.
     */
    private static void move(ConfigSection root, String from, String to) {
        if (!root.has(from)) {
            return;
        }
        if (!root.has(to)) {
            root.set(to, root.get(from));
        }
        root.remove(from);
    }
}
