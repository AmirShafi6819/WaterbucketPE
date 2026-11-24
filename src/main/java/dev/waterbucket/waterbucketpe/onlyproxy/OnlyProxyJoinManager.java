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
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.utils.config.proxy.OnlyProxyJoinSettings;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Only Proxy Join: keeps host firewall rules that force players through the proxy.
 * <p>
 * For every configured downstream server's UDP port the manager maintains exactly two rules
 * (Linux, iptables) or one rule (Windows, Defender Firewall):
 * <ul>
 *     <li>Linux: {@code DROP udp dport <port>} for everyone, with a {@code ACCEPT udp
 *     dport <port> -i lo} inserted before it, so the proxy itself (which dials the
 *     downstreams over loopback) keeps working while every outside packet is dropped.</li>
 *     <li>Windows: a block rule per port - Windows Firewall exempts loopback traffic by
 *     default, so the proxy keeps reaching its downstreams.</li>
 * </ul>
 * All rules carry the {@link #RULE_COMMENT} marker / {@code WaterbucketOPJ-*} name, making
 * apply idempotent ({@code -C} before {@code -I}) and removal complete on disable, shutdown
 * and restart - the operator's firewall is never left dirty.
 * <p>
 * The commands run through {@code ProcessBuilder} without a shell, and the whole operation
 * is failure-isolated: without admin rights (or on hosts where exec is blocked) the manager
 * logs precise manual instructions and the proxy runs unprotected, never broken.
 */
@Log4j2
public class OnlyProxyJoinManager {

    public static final String RULE_COMMENT = "waterbucket-opj";
    public static final String RULE_NAME_PREFIX = "WaterbucketOPJ-";

    private final ProxyServer proxy;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    /** True between a successful apply() and remove(): controls the refresh loop + status. */
    @Getter
    private volatile boolean applied = false;

    public OnlyProxyJoinManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * One downstream port to protect. Ports are collected from every registered server's
     * connect address, deduplicated (several servers may share a port).
     */
    public record PortRule(int port) {
    }

    /**
     * Collects the ports that must be protected. Sorted ascending for a deterministic rule
     * set that diffs cleanly against a previous generation of rules.
     */
    public List<PortRule> collectRules() {
        Set<Integer> ports = new LinkedHashSet<>();
        for (ServerInfo server : this.proxy.getServerInfoMap().values()) {
            int port = server.getAddress().getPort();
            if (port > 0 && port <= 65535) {
                ports.add(port);
            }
        }
        List<PortRule> rules = new ArrayList<>();
        for (int port : ports.stream().sorted().toList()) {
            rules.add(new PortRule(port));
        }
        return rules;
    }

    /**
     * Applies the firewall rules for every downstream port. Idempotent: existing rules are
     * detected with {@code -C} / a netsh lookup and skipped, so calling this repeatedly (boot
     * + refresh loop) is safe.
     *
     * @return true when every rule is in place (or nothing needed doing)
     */
    public boolean apply() {
        OnlyProxyJoinSettings settings = this.settings();
        List<PortRule> rules = this.collectRules();
        if (rules.isEmpty()) {
            log.info("Only Proxy Join: no downstream ports to protect");
            return true;
        }
        if (this.isWindows()) {
            if (!settings.manageFirewall()) {
                log.info("Only Proxy Join: manage_firewall is disabled - verify rules manually for ports {}",
                        this.ports(rules));
                return true;
            }
        } else if (!settings.manageFirewall()) {
            log.info("Only Proxy Join: manage_firewall is disabled - verify rules manually for ports {}",
                    this.ports(rules));
            return true;
        }

        boolean allPresent = true;
        for (PortRule rule : rules) {
            List<List<String>> plan = this.applyPlan(rule);
            for (List<String> command : plan) {
                allPresent &= this.run(command, "apply " + rule.port());
            }
        }
        if (allPresent) {
            this.applied = true;
            log.info("Only Proxy Join active: direct joins to {} are blocked; players must join via port {}",
                    this.ports(rules), settings.getProxyPort());
        }
        return allPresent;
    }

    /**
     * Removes every managed rule (disable, shutdown, restart). Best-effort and complete:
     * each delete is repeated while the check still finds the rule.
     */
    public boolean remove() {
        this.applied = false;
        if (!this.settings().manageFirewall()) {
            return true;
        }
        boolean allGone = true;
        for (PortRule rule : this.collectRules()) {
            for (List<String> command : this.removePlan(rule)) {
                allGone &= this.run(command, "remove " + rule.port());
            }
        }
        log.info("Only Proxy Join rules removed: downstream ports are reachable directly again");
        return allGone;
    }

    /**
     * Re-checks every managed rule and re-adds what disappeared (e.g. a firewall flush).
     * Runs off the hot paths on the proxy scheduler; one refresh at a time.
     */
    public void refresh() {
        if (!this.applied || !this.refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            for (PortRule rule : this.collectRules()) {
                List<List<String>> plan = this.applyPlan(rule);
                for (List<String> command : plan) {
                    this.run(command, "refresh " + rule.port());
                }
            }
        } finally {
            this.refreshing.set(false);
        }
    }

    /**
     * Per-port presence report for {@code /wbonlyproxy status}: true means the rule is in
     * place. On hosts where commands cannot run, every port reports false.
     */
    public Map<Integer, Boolean> status() {
        Map<Integer, Boolean> result = new LinkedHashMap<>();
        for (PortRule rule : this.collectRules()) {
            List<List<String>> plan = this.applyPlan(rule);
            // A rule needs work when its plan is non-empty after the checks inside applyPlan
            result.put(rule.port(), plan.isEmpty());
        }
        return result;
    }

    /**
     * The idempotent apply plan for one port: check-then-add commands in execution order.
     * An empty list means the rule set is already complete. The Linux order matters: the
     * loopback ACCEPT must end up BEFORE the global DROP, so the DROP is inserted first at
     * position 1 and the ACCEPT is inserted on top of it afterwards.
     */
    List<List<String>> applyPlan(PortRule rule) {
        List<List<String>> plan = new ArrayList<>();
        if (this.isWindows()) {
            String name = RULE_NAME_PREFIX + rule.port();
            if (!this.runQuiet(this.windowsCheck(name))) {
                plan.add(this.windowsAdd(name, rule.port()));
            }
            return plan;
        }
        List<String> loopback = List.of("iptables", "-w", "-C", "INPUT",
                "-i", "lo", "-p", "udp", "--dport", String.valueOf(rule.port()),
                "-m", "comment", "--comment", RULE_COMMENT, "-j", "ACCEPT");
        if (!this.runQuiet(loopback)) {
            plan.add(List.of("iptables", "-w", "-I", "INPUT", "1",
                    "-i", "lo", "-p", "udp", "--dport", String.valueOf(rule.port()),
                    "-m", "comment", "--comment", RULE_COMMENT, "-j", "ACCEPT"));
        }
        List<String> drop = List.of("iptables", "-w", "-C", "INPUT",
                "-p", "udp", "--dport", String.valueOf(rule.port()),
                "-m", "comment", "--comment", RULE_COMMENT, "-j", "DROP");
        if (!this.runQuiet(drop)) {
            plan.add(List.of("iptables", "-w", "-I", "INPUT", "2",
                    "-p", "udp", "--dport", String.valueOf(rule.port()),
                    "-m", "comment", "--comment", RULE_COMMENT, "-j", "DROP"));
        }
        return plan;
    }

    /**
     * The removal plan for one port: exact-match deletes for every rule the apply step may
     * have created.
     */
    List<List<String>> removePlan(PortRule rule) {
        List<List<String>> plan = new ArrayList<>();
        if (this.isWindows()) {
            plan.add(this.windowsDelete(RULE_NAME_PREFIX + rule.port()));
            return plan;
        }
        plan.add(List.of("iptables", "-w", "-D", "INPUT",
                "-i", "lo", "-p", "udp", "--dport", String.valueOf(rule.port()),
                "-m", "comment", "--comment", RULE_COMMENT, "-j", "ACCEPT"));
        plan.add(List.of("iptables", "-w", "-D", "INPUT",
                "-p", "udp", "--dport", String.valueOf(rule.port()),
                "-m", "comment", "--comment", RULE_COMMENT, "-j", "DROP"));
        return plan;
    }

    private List<String> windowsCheck(String name) {
        return List.of("netsh", "advfirewall", "firewall", "show", "rule", "name=" + name);
    }

    private List<String> windowsAdd(String name, int port) {
        return List.of("netsh", "advfirewall", "firewall", "add", "rule",
                "name=" + name, "dir=in", "action=block", "protocol=udp",
                "localport=" + port, "profile=any");
    }

    private List<String> windowsDelete(String name) {
        return List.of("netsh", "advfirewall", "firewall", "delete", "rule", "name=" + name);
    }

    /**
     * Runs one firewall command. Output is captured (never inherited) so failures stay in
     * the proxy log with context instead of spilling into the console; the exception text is
     * logged as the reason.
     *
     * @return true when the command exited successfully
     */
    boolean run(List<String> command, String context) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            boolean ok = process.waitFor() == 0;
            if (!ok) {
                log.warn("Only Proxy Join: command for {} failed ({}): {} - if this is a permissions "
                        + "problem, run the proxy as root/admin once or apply the rules manually",
                        context, process.exitValue(), output.isEmpty() ? "no output" : output);
            } else {
                log.debug("Only Proxy Join: {} ok{}", context, output.isEmpty() ? "" : ": " + output);
            }
            return ok;
        } catch (Throwable t) {
            log.warn("Only Proxy Join: cannot run {} ({}). Apply the firewall rules manually if you "
                    + "need direct-join protection", context, t.toString());
            return false;
        }
    }

    /** Quiet probe for the check commands: never logs, exit code only. */
    boolean runQuiet(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private OnlyProxyJoinSettings settings() {
        return this.proxy.getConfiguration().getOnlyProxyJoinSettings();
    }

    private String ports(List<PortRule> rules) {
        StringBuilder builder = new StringBuilder();
        for (PortRule rule : rules) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(rule.port());
        }
        return builder.toString();
    }
}
