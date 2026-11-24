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

package dev.waterbucket.waterbucketpe;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.ConsoleCommandSender;
import dev.waterbucket.waterbucketpe.command.DefaultCommandMap;
import dev.waterbucket.waterbucketpe.command.SimpleCommandMap;
import dev.waterbucket.waterbucketpe.command.utils.CommandUtils;
import dev.waterbucket.waterbucketpe.console.TerminalConsole;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.defaults.DispatchCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.NetworkRegisterEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyStartEvent;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.network.EventLoops;
import dev.waterbucket.waterbucketpe.network.NetworkInterface;
import dev.waterbucket.waterbucketpe.network.NetworkMetrics;
import dev.waterbucket.waterbucketpe.network.NetworkStartupException;
import dev.waterbucket.waterbucketpe.network.RakNetInterface;
import dev.waterbucket.waterbucketpe.network.nethernet.NetherNetInterface;
import dev.waterbucket.waterbucketpe.network.nethernet.SignalingService;
import dev.waterbucket.waterbucketpe.network.connection.codec.compression.CompressionType;
import dev.waterbucket.waterbucketpe.network.connection.codec.initializer.ProxiedSessionInitializer;
import dev.waterbucket.waterbucketpe.network.connection.codec.query.QueryHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.DefaultForcedHostHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.IForcedHostHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.IJoinHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.IReconnectHandler;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolCodecs;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import dev.waterbucket.waterbucketpe.network.protocol.updaters.CodecUpdaterCommands;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfoMap;
import dev.waterbucket.waterbucketpe.packs.PackManager;
import dev.waterbucket.waterbucketpe.panel.PanelServer;
import dev.waterbucket.waterbucketpe.player.PlayerManager;
import dev.waterbucket.waterbucketpe.restart.RestartManager;
import dev.waterbucket.waterbucketpe.sleep.SleepModeManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.PluginManager;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.security.SecurityManager;
import dev.waterbucket.waterbucketpe.utils.ConfigurationManager;
import dev.waterbucket.waterbucketpe.utils.ThreadFactoryBuilder;
import dev.waterbucket.waterbucketpe.utils.bstats.Metrics;
import dev.waterbucket.waterbucketpe.utils.config.LangConfig;
import dev.waterbucket.waterbucketpe.utils.config.proxy.NetherNetSettings;
import dev.waterbucket.waterbucketpe.utils.config.proxy.NetworkSettings;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import dev.waterbucket.waterbucketpe.utils.reporting.ErrorReporting;
import dev.waterbucket.waterbucketpe.utils.types.TextContainer;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;
import io.netty.channel.EventLoopGroup;
import io.netty.util.internal.EmptyArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import net.cubespace.Yamler.Config.InvalidConfigurationException;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.TrustedProxies;
import org.cloudburstmc.protocol.common.util.Preconditions;

public class ProxyServer {
    /**
     * How often the ghost-player sweep runs (15 seconds in ticks).
     */
    private static final int GHOST_SWEEP_PERIOD_TICKS = 20 * 15;

    @Getter
    private static ProxyServer instance;

    @Getter
    private final Path dataPath;
    @Getter
    private final Path pluginPath;
    private final Path packsPath;

    @Getter
    private final MainLogger logger;
    private final TerminalConsole console;

    @Getter
    private final ConfigurationManager configurationManager;
    @Getter
    private final WaterbucketScheduler scheduler;
    @Getter
    private final PlayerManager playerManager;
    @Getter
    private final PluginManager pluginManager;
    @Getter
    private final EventManager eventManager;
    @Getter
    private final PackManager packManager;

    @Getter
    private final ServerInfoMap serverInfoMap = new ServerInfoMap();

    private final List<NetworkInterface> interfaces = new ObjectArrayList<>();
    private NetherNetInterface netherNetInterface;
    private IpRangeSet proxyProtocolProxies;
    private IpRangeSet webrtcProxyProtocolRelays;

    @Getter
    private QueryHandler queryHandler;

    @Getter
    private CommandMap commandMap;
    private final ConsoleCommandSender commandSender;

    @Getter
    private final SecurityManager securityManager;
    @Getter
    private final RestartManager restartManager;
    @Getter
    private final SleepModeManager sleepModeManager;
    @Getter
    private final ErrorReporting errorReporting;
    private PanelServer panelServer;

    @Setter
    @Getter
    private IReconnectHandler reconnectHandler;
    @Setter
    @Getter
    private IJoinHandler joinHandler;
    @Setter
    @Getter
    private IForcedHostHandler forcedHostHandler;
    @Getter
    private NetworkMetrics networkMetrics;
    @Getter
    private final EventLoopGroup bossEventLoopGroup;
    @Getter
    private final EventLoopGroup workerEventLoopGroup;
    private final ScheduledExecutorService tickExecutor;
    private ScheduledFuture<?> tickFuture;
    private volatile boolean shutdown = false;

    /**
     * Runtime maximum-player override set through {@code setMaxPlayers}; {@code -1} means
     * "use listener.max_players from config.yml". Volatile: plugins set it from their own
     * threads while ping/query/logins read it on the network threads.
     */
    private volatile int maxPlayersOverride = -1;
    /**
     * Written by the tick thread, read from any thread that schedules tasks; volatile so a
     * scheduled task's next-run tick is never computed from a stale value.
     */
    @Getter
    private volatile int currentTick = 0;

    public ProxyServer(MainLogger logger, String filePath, String pluginPath) throws InvalidConfigurationException {
        instance = this;
        this.logger = logger;
        this.dataPath = Paths.get(filePath);
        this.pluginPath = Paths.get(pluginPath);
        this.packsPath = this.dataPath.resolve("packs");

        if (!this.pluginPath.toFile().exists()) {
            if (this.pluginPath.toFile().mkdirs())
                this.logger.info("Created Plugin Folder at " + this.pluginPath);
            else
                this.logger.warning("Could not create Plugin Folder at " + this.pluginPath);
        }

        if (!this.packsPath.toFile().exists()) {
            if (this.packsPath.toFile().mkdirs())
                this.logger.info("Created Packs Folder at " + this.packsPath);
            else
                this.logger.warning("Could not create Packs Folder at " + this.packsPath);
        }

        this.configurationManager = new ConfigurationManager(this);
        this.configurationManager.loadProxyConfig();
        this.configurationManager.loadLanguage();
        this.configurationManager.loadIngameMessages();
        this.errorReporting = new ErrorReporting(this);

        if (!this.getNetworkSettings().enableIpv6()) {
            // Some devices and networks may not support IPv6
            System.setProperty("java.net.preferIPv4Stack", "true");
        }

        // Make the runtime debug flag follow the operator's config in both directions. Previously
        // only enable_debug=true was forwarded, so a SNAPSHOT/no-git source build kept debug-level
        // plugin loggers and raw packet logging enabled even with enable_debug: false.
        WaterbucketPE.version().debug(this.getConfiguration().isDebug());

        CompressionType compression = this.getConfiguration().getCompression();
        if (compression.getBedrockAlgorithm() == null) {
            this.logger.error("Bedrock compression supports only ZLIB or Snappy! Currently provided " + compression + ", defaulting to ZLIB!");
            this.getConfiguration().setCompression(CompressionType.ZLIB);
        }

        ThreadFactoryBuilder builder = ThreadFactoryBuilder
                .builder()
                .format("WaterbucketTick Executor - #%d")
                // Internal pools must never keep the JVM alive on their own; the shutdown
                // path terminates them explicitly.
                .daemon(true)
                .build();
        this.tickExecutor = Executors.newScheduledThreadPool(1, builder);

        EventLoops.ChannelType channelType = EventLoops.getChannelType();
        this.logger.info("Using " + channelType.name() + " channel implementation as default!");
        for (EventLoops.ChannelType type : EventLoops.ChannelType.values()) {
            this.logger.debug("Supported " + type.name() + " channels: " + type.isAvailable());
        }

        ThreadFactoryBuilder workerFactory = ThreadFactoryBuilder.builder()
                .format("Bedrock Listener - #%d")
                .priority(5)
                .daemon(true)
                .build();
        ThreadFactoryBuilder bossFactory = ThreadFactoryBuilder.builder()
                .format("Network Listener - #%d")
                .priority(8)
                .daemon(true)
                .build();
        // Netty defaults to 2x core count threads per group. The RakNet listener binds one channel per
        // core with SO_REUSEPORT, so core-count worker loops still give every channel a dedicated loop,
        // while halving thread stacks, selectors and context switching. The boss group only registers
        // channels, so a fraction of the core count is plenty for it.
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.workerEventLoopGroup = channelType.newEventLoopGroup(coreCount, workerFactory);
        this.bossEventLoopGroup = channelType.newEventLoopGroup(Math.max(2, coreCount / 2), bossFactory);

        // Default Handlers
        this.forcedHostHandler = new DefaultForcedHostHandler();
        this.pluginManager = new PluginManager(this);
        this.scheduler = new WaterbucketScheduler(this);
        this.playerManager = new PlayerManager(this);
        this.eventManager = new EventManager(this);
        this.packManager = new PackManager(this);
        this.securityManager = new SecurityManager(this);
        this.restartManager = new RestartManager(this);
        this.sleepModeManager = new SleepModeManager(this);
        this.commandSender = new ConsoleCommandSender(this);
        this.commandMap = new DefaultCommandMap(this, SimpleCommandMap.DEFAULT_PREFIX);
        this.console = new TerminalConsole(this);

        this.pluginManager.loadAllPlugins();
        this.configurationManager.loadServerInfos(this.serverInfoMap);
        this.reconnectHandler = this.configurationManager.loadServiceProvider(this.getConfiguration().getReconnectHandler(), IReconnectHandler.class, this.pluginManager);
        this.joinHandler = this.configurationManager.loadServiceProvider(this.getConfiguration().getJoinHandler(), IJoinHandler.class, this.pluginManager);

        this.boot();
    }

    public void reloadPackManager() {
        try {
            this.configurationManager.loadProxyConfig();
        } catch (InvalidConfigurationException e) {
            this.logger.error("Failed to reload proxy config, keeping the currently loaded packs!", e);
            return;
        }
        this.packManager.reloadPacks();
    }

    private void boot() {
        // Headless: never start the console read loop. JLine's dumb terminal returns null from
        // readLine immediately, so the loop spun at full speed printing prompts (and burning a
        // core's worth of CPU + log I/O) in docker/systemd/nohup runs. Interactive terminals are
        // unaffected.
        if (TerminalConsole.isInteractive()) {
            this.console.getConsoleThread().start();
        } else {
            this.logger.info("No interactive terminal detected - console input disabled (use the web panel for commands)");
        }
        this.pluginManager.enableAllPlugins();
        if (Boolean.parseBoolean(System.getProperty("disableFastCodec", "false"))) {
            this.logger.warning("Fast codec is disabled! This may impact the proxy performance!");
        } else {
            this.logger.info("Using fast codec for improved performance and stability!");
            if (this.getConfiguration().injectCommands()) {
                ProtocolCodecs.addUpdater(new CodecUpdaterCommands());
            }

            for (ProtocolVersion version : ProtocolVersion.values()) {
                version.setBedrockCodec(ProtocolCodecs.buildCodec(version.getDefaultCodec()));
            }
        }

        if (this.getConfiguration().isEnableAnonymousStatistics()) {
            this.logger.info("Enabling anonymous statistics.");
            try {
                Metrics.startMetrics(this, this.getConfiguration());
            } catch (Exception e) {
                // bStats is an optional telemetry feature; a failure to create its config file
                // (read-only filesystem) must not abort the whole boot.
                this.logger.error("Failed to initialize anonymous statistics; continuing without them", e);
            }
        }

        if (this.getConfiguration().enableResourcePacks()) {
            this.packManager.loadPacks(this.packsPath);
        }

        InetSocketAddress bindAddress = this.getConfiguration().getBindAddress();
        bindAddress = new InetSocketAddress(bindAddress.getHostString(), bindAddress.getPort());
        this.logger.info("Binding to {}", bindAddress);

        ProxyConfig.Transport transport = this.getConfiguration().getTransport();
        // Query answers on the RakNet pipeline
        if (this.getConfiguration().enableQuery() && transport.raknet()) {
            this.queryHandler = new QueryHandler(this);
        }

        if (transport.raknet()) {
            this.registerInterface(new RakNetInterface(this));
        }
        // Registered even without NetherNet so plugins can see it; start() does nothing then.
        this.netherNetInterface = new NetherNetInterface(this);
        this.registerInterface(this.netherNetInterface);

        try {
            this.bootNetworks(bindAddress);
        } catch (NetworkStartupException e) {
            this.logger.error("Failed to bind to primary address {}, shutting down", bindAddress, e);
            // Report failure to process supervisors: shutdown(0) used to exit with status 0,
            // making Docker/systemd restart policies treat the failed bind as a clean exit.
            this.shutdown(1);
            return;
        }
        for (Integer port : this.getConfiguration().getAdditionalPorts()) {
            try {
                if (port == null || port < 1 || port > 65535) {
                    this.logger.error("Ignoring invalid additional port {} (must be 1-65535)", port);
                    continue;
                }
                InetSocketAddress additionalBind = new InetSocketAddress(bindAddress.getHostString(), port);
                this.bootNetworks(additionalBind);
            } catch (NetworkStartupException e) {
                this.logger.error("Unable to bind to additional port {}, skipping", port, e);
            } catch (IllegalArgumentException e) {
                this.logger.error("Ignoring invalid additional port {}", port, e);
            }
        }

        ProxyStartEvent event = new ProxyStartEvent(this);
        this.eventManager.callEvent(event);

        this.logger.debug("Upstream <-> Proxy compression level " + this.getConfiguration().getUpstreamCompression());
        this.logger.debug("Downstream <-> Proxy compression level " + this.getConfiguration().getDownstreamCompression());
        this.logger.debug("MTU Settings: max_user=" + this.getNetworkSettings().getMaximumMtu() + " max_server=" + this.getNetworkSettings().getMaximumDownstreamMtu());
        this.logger.debug("RakNet Cookies: enabled=" + this.getNetworkSettings().enableCookies());
        this.logger.debug("PROXY protocol: enabled=" + this.getNetworkSettings().proxyProtocol()
                + " webrtc_relays=" + !this.getWebrtcProxyProtocolRelays().isEmpty());

        ProxiedSessionInitializer.ZLIB_RAW_STRATEGY.getDefaultCompression().setLevel(this.getConfiguration().getUpstreamCompression());
        ProxiedSessionInitializer.ZLIB_STRATEGY.getDefaultCompression().setLevel(this.getConfiguration().getUpstreamCompression());
        // The downstream level is applied per connection with its own zlib strategy
        // (ProxiedClientSessionInitializer / BedrockClientConnection use
        // downstream_compression), so nothing further is needed here.

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        this.tickFuture = this.tickExecutor.scheduleAtFixedRate(this::tickProcessor, 50, 50, TimeUnit.MILLISECONDS);

        // Periodically evict ghost registrations whose disconnect event was never processed;
        // they would otherwise stay registered forever and block reconnects for that identity.
        this.scheduler.scheduleRepeating(this.playerManager::sweepDisconnectedPlayers, GHOST_SWEEP_PERIOD_TICKS, true);

        // Sleep mode heartbeat: once a second, decides when an empty proxy falls asleep
        this.scheduler.scheduleRepeating(this.sleepModeManager::tick, SleepModeManager.HEARTBEAT_TICKS, true);

        this.startPanel();

        // Move the first join's one-time cost (JCE crypto init, zlib, packet class loads) to a
        // background thread right now, so the first real player does not pay for it with a
        // visible CPU/RAM spike on the dashboard.
        StartupWarmup.kickOff();
    }

    private void startPanel() {
        if (!this.getConfiguration().enablePanel()) {
            return;
        }
        try {
            this.panelServer = new PanelServer(this, this.getConfiguration().panelToken());
            this.panelServer.start(this.getConfiguration().panelBindAddress(), this.getConfiguration().panelPort());
        } catch (Exception e) {
            this.logger.error("Failed to start the web panel; the proxy keeps running without it", e);
            this.panelServer = null;
        }
    }

    private void bootNetworks(InetSocketAddress address) throws NetworkStartupException {
        for (NetworkInterface interfaze : this.interfaces) {
            interfaze.start(address);
        }
    }

    /**
     * Registers a network interface.
     * <p>
     * Interfaces registered after boot (e.g., in a ProxyStartEvent handler) must be started manually by calling {@link NetworkInterface#start(InetSocketAddress)}.
     */
    public void registerInterface(NetworkInterface interfaze) {
        if (shutdown) {
            return;
        }
        NetworkRegisterEvent event = new NetworkRegisterEvent(interfaze);
        this.eventManager.callEvent(event);
        if (!event.isCancelled()) {
            this.interfaces.add(interfaze);
            // Plugins are enabled before the interfaces exist, so metrics are usually already set.
            if (this.networkMetrics != null) {
                interfaze.setNetworkMetrics(this.networkMetrics);
            }
        }
    }

    /**
     * Returns an unmodifiable list of registered network interfaces.
     */
    public List<NetworkInterface> getInterfaces() {
        return Collections.unmodifiableList(this.interfaces);
    }

    private void tickProcessor() {
        if (this.shutdown && !this.tickFuture.isCancelled()) {
            this.tickFuture.cancel(false);
            this.interfaces.forEach(NetworkInterface::shutdown);
        }

        try {
            this.onTick(++this.currentTick);
            this.interfaces.forEach(NetworkInterface::tick);
        } catch (Throwable e) {
            // scheduleAtFixedRate silently stops on any throwable that escapes: catching only
            // Exception let one Error permanently kill the tick loop (scheduler, sweeps, all
            // repeating tasks) with no log and no shutdown. Log and keep ticking.
            this.logger.error("Error while ticking proxy!", e);
        }
    }

    private void onTick(int currentTick) {
        this.scheduler.onTick(currentTick);
    }

    public void shutdown() {
        this.shutdown(0);
    }

    /**
     * Tears the proxy down and force-exits with the given status code. Status 0 is the normal
     * shutdown (console "end", SIGTERM); a non-zero status reports a failed startup to process
     * supervisors.
     */
    public void shutdown(int status) {
        // Guard the check-then-act with a lock: a SIGTERM hook and a console/panel "end"
        // command can otherwise both pass the flag check and run the teardown twice.
        synchronized (this) {
            if (this.shutdown) {
                return;
            }
            this.shutdown = true;
        }

        try {
            this.shutdown0();
        } catch (Exception e) {
            this.logger.error("Unable to shutdown proxy gracefully", e);
        } finally {
            WaterbucketPE.shutdownHook(status);
        }
    }

    private void shutdown0() throws Exception {
        if (this.panelServer != null) {
            this.panelServer.shutdown();
        }
        this.pluginManager.disableAllPlugins();
        String disconnectReason = new TranslationContainer("waterbucket.server.shutdown").getTranslated();
        for (Map.Entry<UUID, ProxiedPlayer> player : this.playerManager.getPlayers().entrySet()) {
            this.logger.info("Disconnecting " + player.getValue().getName());
            player.getValue().disconnect(disconnectReason);
        }
        // The caller may have been interrupted on its way in (a panel "end" command used to run
        // the shutdown on the panel executor itself, which shutdownNow() interrupts mid-flight).
        // Swallow the flag so the whole teardown below always completes.
        try {
            Thread.sleep(500); // Give small delay to send packet
        } catch (InterruptedException e) {
            this.logger.debug("Shutdown delay interrupted; continuing teardown");
        }

        this.console.getConsoleThread().interrupt();
        // Give the console thread a moment to run JLine's exit path (terminal attribute restore
        // and history flush). Without the join, the halt below could race the restore and leave
        // the operator's shell in raw mode after `end`.
        try {
            this.console.getConsoleThread().join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.console.close();
        this.tickExecutor.shutdown();

        try {
            // Close the network interfaces first: closing a connection fires disconnect events,
            // so the event executor must still be alive here. Shutting it down before the
            // interfaces made every pending PlayerDisconnectedEvent fail inside disconnect().
            for (NetworkInterface interfaze : this.interfaces) {
                interfaze.shutdown();
            }
        } catch (Exception e) {
            this.logger.error("Error while shutting down ProxyServer", e);
        }

        this.scheduler.shutdown();
        this.eventManager.getThreadedExecutor().shutdown();

        if (Metrics.get() != null) {
            Metrics.get().shutdown();
        }

        if (this.errorReporting != null) {
            try {
                this.errorReporting.close();
            } catch (Exception e) {
                this.logger.debug("Error reporting shutdown failed", e);
            }
        }

        if (this.tickFuture != null && !this.tickFuture.isCancelled()) {
            this.logger.info("Interrupting scheduler!");
            this.tickFuture.cancel(true);
        }
        this.logger.info("Shutdown complete!");
    }

    public String translate(TextContainer textContainer) {
        return this.getLanguageConfig().translateContainer(textContainer);
    }

    public boolean handlePlayerCommand(ProxiedPlayer player, String message) {
        // Give plugins a hook on every command a player types, before the proxy decides
        // whether the command belongs to it or to the downstream server. Canceling consumes
        // the command; rewriting applies to both proxy and downstream commands alike.
        PlayerCommandEvent commandEvent = new PlayerCommandEvent(player, message);
        this.eventManager.callEvent(commandEvent);
        if (commandEvent.isCancelled()) {
            return true;
        }
        message = commandEvent.getCommand();
        if (!this.commandMap.handleMessage(player, message)) {
            return false;
        }
        return this.dispatchCommand(player, message.substring(this.commandMap.getCommandPrefix().length()));
    }

    public boolean dispatchCommand(CommandSender sender, String message) {
        if (message.trim().isEmpty()) {
            return false;
        }
        // A console/panel command is contact: an asleep proxy wakes before executing it
        this.sleepModeManager.onActivity();

        String[] args = message.split(" ");
        Command command = this.commandMap.getCommand(args[0]);

        String[] shiftedArgs;
        if (command != null && command.getSettings().isQuoteAware()) { // Quote aware parsing
            List<String> arguments = CommandUtils.parseArguments(message);
            // A plugin-registered quote-aware command named purely of quote characters parses
            // to an empty argument list; remove(0) threw out of the player command path.
            if (!arguments.isEmpty()) {
                arguments.remove(0);
            }
            shiftedArgs = arguments.toArray(String[]::new);
        } else {
            shiftedArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : EmptyArrays.EMPTY_STRINGS;
        }
        DispatchCommandEvent event = new DispatchCommandEvent(sender, args[0], shiftedArgs, command);
        this.eventManager.callEvent(event);

        // Canceling only skips the execution, the consume state decides if downstream still sees the command
        boolean consumed = !event.isCancelled() && this.commandMap.handleCommand(sender, command, args[0], shiftedArgs);
        return event.isConsumed(consumed);
    }

    public boolean isRunning() {
        return !this.shutdown;
    }

    public ProxyConfig getConfiguration() {
        return this.configurationManager.getProxyConfig();
    }

    public NetworkSettings getNetworkSettings() {
        return this.configurationManager.getProxyConfig().getNetworkSettings();
    }

    /**
     * The proxies RakNet and NetherNet signaling take PROXY protocol headers from:
     * network_settings.proxy_protocol_whitelisted_ips. Parsed once, as an entry may be a URL.
     */
    public synchronized IpRangeSet getProxyProtocolProxies() {
        if (this.proxyProtocolProxies == null) {
            this.proxyProtocolProxies = TrustedProxies.parse(this.getNetworkSettings().getProxyProtocolWhitelistedIps());
        }
        return this.proxyProtocolProxies;
    }

    /**
     * The relays in front of the NetherNet media port that send PROXY protocol headers:
     * network_settings.webrtc_proxy_protocol_relays. Parsed once, as an entry may be a URL.
     */
    public synchronized IpRangeSet getWebrtcProxyProtocolRelays() {
        if (this.webrtcProxyProtocolRelays == null) {
            this.webrtcProxyProtocolRelays = TrustedProxies.parse(this.getNetworkSettings().getWebrtcProxyProtocolRelays());
        }
        return this.webrtcProxyProtocolRelays;
    }

    public NetherNetSettings getNetherNetSettings() {
        return this.configurationManager.getProxyConfig().getNetherNetSettings();
    }

    /**
     * Calls {@link ProxyPingEvent} for what this proxy tells players about itself, so a plugin's
     * changes reach RakNet pings, NetherNet advertisements and the NXS listing alike.
     *
     * @param address The client asking, or null when none did, as for the NXS listing
     */
    public ProxyPingEvent callPingEvent(InetSocketAddress address) {
        // A server-list ping is contact: an asleep proxy must wake before answering
        this.sleepModeManager.onActivity();
        ProxyConfig config = this.getConfiguration();
        ProxyPingEvent event = new ProxyPingEvent(
                config.getMotd(),
                config.getSubMotd(),
                "Survival",
                "MCPE",
                ProtocolVersion.latest().getMinecraftVersion(),
                this.getPlayerManager().getPlayers().values(),
                this.getMaxPlayers(),
                address);
        this.getEventManager().callEvent(event);
        return event;
    }

    /**
     * Feeds NetherNet SDP offers into the proxy from outside, for {@code signaling.mode: external}
     * or to answer on behalf of another node. Null until the proxy has booted.
     */
    public SignalingService getSignalingService() {
        return this.netherNetInterface;
    }

    public NetherNetInterface getNetherNetInterface() {
        return this.netherNetInterface;
    }

    public LangConfig getLanguageConfig() {
        return this.configurationManager.getLangConfig();
    }

    public ProxiedPlayer getPlayer(UUID uuid) {
        return this.playerManager.getPlayer(uuid);
    }

    public ProxiedPlayer getPlayer(String playerName) {
        return this.playerManager.getPlayer(playerName);
    }

    public Map<UUID, ProxiedPlayer> getPlayers() {
        return this.playerManager.getPlayers();
    }

    /**
     * Velocity-style view of every online player. Equivalent to
     * {@code getPlayers().values()} - the live collection backing the registry, kept
     * up-to-date by the manager.
     */
    public Collection<ProxiedPlayer> getAllPlayers() {
        return this.playerManager.getPlayers().values();
    }

    /**
     * Velocity-style alias for {@link EventManager#callEvent(Event)}: fires the event through
     * the subscriber pipeline and returns the pipeline future for async events. Semantics,
     * priorities and the async-completion contract are identical to callEvent.
     */
    public <T extends Event> CompletableFuture<T> fire(T event) {
        return this.getEventManager().callEvent(event);
    }

    /**
     * @param name the exact player name, case-insensitive
     * @return the online player whose name equals {@code name} ignoring case, or null
     */
    public ProxiedPlayer getPlayerExact(String name) {
        ProxiedPlayer player = this.playerManager.getPlayer(name);
        return player != null && player.getName().equalsIgnoreCase(name) ? player : null;
    }

    /**
     * @param name a partial player name, case-insensitive
     * @return every online player whose name contains {@code name} ignoring case
     */
    public List<ProxiedPlayer> matchPlayer(String name) {
        String query = name.toLowerCase(Locale.ROOT);
        List<ProxiedPlayer> matched = new ObjectArrayList<>();
        for (ProxiedPlayer player : this.getPlayers().values()) {
            String playerName = player.getName().toLowerCase(Locale.ROOT);
            if (playerName.contains(query)) {
                matched.add(player);
            }
        }
        return matched;
    }

    /**
     * @return the effective maximum player count: the runtime override when one was set,
     * otherwise {@code listener.max_players} from config.yml. {@code 0} or less means unlimited.
     */
    public int getMaxPlayers() {
        return resolveMaxPlayers(this.maxPlayersOverride, this.getConfiguration().getMaxPlayerCount());
    }

    /**
     * Sets the maximum player count at runtime, overriding {@code listener.max_players}.
     * The change applies to new logins and to ping/query responses immediately.
     *
     * @param maxPlayers the new maximum player count, {@code 0} disables the limit
     */
    public void setMaxPlayers(int maxPlayers) {
        this.setMaxPlayers(maxPlayers, true);
    }

    /**
     * Sets the maximum player count at runtime.
     *
     * @param maxPlayers the new maximum player count, {@code 0} disables the limit
     * @param persist whether the change is written back to config.yml and survives restarts
     */
    public void setMaxPlayers(int maxPlayers, boolean persist) {
        Preconditions.checkArgument(maxPlayers >= 0, "maxPlayers can not be negative!");
        this.maxPlayersOverride = maxPlayers;
        if (persist) {
            ProxyConfig config = this.getConfiguration();
            config.setMaxPlayerCount(maxPlayers);
            try {
                config.save();
            } catch (InvalidConfigurationException e) {
                this.getLogger().error("Could not persist max_players to config.yml!", e);
            }
        }
        this.getLogger().info("Maximum player count set to {}{}", maxPlayers,
                persist ? " (saved to config.yml)" : " (runtime only)");
    }

    /**
     * Pure resolver kept package-private so tests can exercise the override semantics.
     *
     * @return the override when it is zero or greater, otherwise the configured value
     */
    static int resolveMaxPlayers(int override, int configured) {
        return override >= 0 ? override : configured;
    }

    /**
     * Whether the proxy can still accept a login. The proxy is full when the effective
     * maximum player count is above zero, reached, and the player does not hold the
     * {@code waterbucket.bypass.maxplayers} permission (or the full permission).
     */
    public boolean isFull(ProxiedPlayer player) {
        int max = this.getMaxPlayers();
        if (max <= 0 || this.getPlayers().size() < max) {
            return false;
        }
        return player == null || !player.hasPermission("waterbucket.bypass.maxplayers");
    }

    /**
     * Sends a message to every online player and the console.
     *
     * @param message the raw text to broadcast
     */
    public void broadcastMessage(String message) {
        for (ProxiedPlayer player : this.getPlayers().values()) {
            player.sendMessage(message);
        }
        this.commandSender.sendMessage(message);
    }

    /**
     * Sends a (translatable) message container to every online player and the console.
     *
     * @param message the container to broadcast
     */
    public void broadcastMessage(TextContainer message) {
        for (ProxiedPlayer player : this.getPlayers().values()) {
            player.sendMessage(message);
        }
        this.commandSender.sendMessage(message);
    }

    @Deprecated
    public ServerInfo getServer(String serverName) {
        return this.serverInfoMap.get(serverName.toLowerCase());
    }

    /**
     * Allows to add servers dynamically to server map
     *
     * @return if server was registered
     */
    public boolean registerServerInfo(ServerInfo serverInfo) {
        Preconditions.checkNotNull(serverInfo, "ServerInfo can not be null!");
        return this.serverInfoMap.putIfAbsent(serverInfo.getServerName(), serverInfo) == null;
    }

    /**
     * Remove server from server map
     *
     * @return removed ServerInfo or null
     */
    public ServerInfo removeServerInfo(String serverName) {
        Preconditions.checkNotNull(serverName, "ServerName can not be null!");
        return this.serverInfoMap.remove(serverName);
    }

    public ServerInfo getServerInfo(String serverName) {
        Preconditions.checkNotNull(serverName, "ServerName can not be null!");
        return this.serverInfoMap.get(serverName);
    }

    public <T extends ServerInfo> T getServerInfo(String serverName, Class<T> implementation) {
        Preconditions.checkNotNull(serverName, "ServerName can not be null!");
        Preconditions.checkNotNull(implementation, "Implementation class can not be null!");

        ServerInfo serverInfo = this.serverInfoMap.get(serverName);
        if (serverInfo != null && !implementation.isAssignableFrom(serverInfo.getClass())) {
            throw new IllegalStateException("Server " + serverName + " is not type of " + implementation.getSimpleName());
        }
        return (T) serverInfo;
    }

    /**
     * Get ServerInfo by address and port. Matching never blocks (no DNS on the calling thread),
     * so this is safe to use from Netty event loops, e.g. while handling transfers.
     *
     * @return ServerInfo instance of matched server
     */
    public ServerInfo getServerInfo(String address, int port) {
        Preconditions.checkNotNull(address, "Address can not be null!");
        return this.serverInfoMap.get(address, port);
    }

    /**
     * Get ServerInfo instance using hostname
     *
     * @return ServerInfo assigned to forced host
     */
    public ServerInfo getForcedHost(String serverHostname) {
        Preconditions.checkNotNull(serverHostname, "ServerHostname can not be null!");
        String serverName = null;

        for (String forcedHost : this.getConfiguration().getForcedHosts().keySet()) {
            if (forcedHost.equalsIgnoreCase(serverHostname)) {
                serverName = this.getConfiguration().getForcedHosts().get(forcedHost);
                break;
            }
        }
        return serverName == null ? null : this.serverInfoMap.get(serverName);
    }

    /**
     * Get all registered ServerInfo instances
     *
     * @return an unmodifiable collection containing all registered ServerInfo instances
     */
    public Collection<ServerInfo> getServers() {
        return this.serverInfoMap.values();
    }

    public void setCommandMap(CommandMap commandMap) {
        Preconditions.checkNotNull(commandMap, "Command map can not be null!");
        this.commandMap = commandMap;
    }

    public ConsoleCommandSender getConsoleSender() {
        return this.commandSender;
    }

    /**
     * @param metrics the metrics to report to, or null to stop reporting
     */
    public void setNetworkMetrics(NetworkMetrics metrics) {
        this.networkMetrics = metrics;
        // Listener wide metrics live on the bound channels, which only the interfaces hold.
        for (NetworkInterface interfaze : this.interfaces) {
            interfaze.setNetworkMetrics(metrics);
        }
    }

    @Deprecated
    public boolean isDebug() {
        return WaterbucketPE.version().debug();
    }

}
