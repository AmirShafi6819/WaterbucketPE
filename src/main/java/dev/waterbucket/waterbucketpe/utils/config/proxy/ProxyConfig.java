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

package dev.waterbucket.waterbucketpe.utils.config.proxy;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.connection.codec.compression.CompressionType;
import dev.waterbucket.waterbucketpe.utils.config.ServerList;
import dev.waterbucket.waterbucketpe.utils.config.serializer.CompressionAlgorithmConverter;
import dev.waterbucket.waterbucketpe.utils.config.serializer.InetSocketAddressConverter;
import dev.waterbucket.waterbucketpe.utils.config.serializer.LowercaseEnumConverter;
import dev.waterbucket.waterbucketpe.utils.config.serializer.ServerEntryConverter;
import dev.waterbucket.waterbucketpe.utils.config.serializer.ServerListConverter;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.cubespace.Yamler.Config.YamlConfig;
import net.cubespace.Yamler.Config.*;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.*;

@Getter @Setter
@SerializeOptions(skipFailedObjects = true)
public class ProxyConfig extends YamlConfig {

    /**
     * Server-list ping passthrough modes, named like Velocity's ping-passthrough.
     */
    public enum PingPassthrough {
        DISABLED,
        DESCRIPTION,
        ALL
    }

    /**
     * How players connect. Named like Geyser's bedrock.transport.
     */
    public enum Transport {
        RAKNET,
        NETHERNET,
        BOTH;

        public boolean raknet() {
            return this == RAKNET || this == BOTH;
        }

        public boolean nethernet() {
            return this == NETHERNET || this == BOTH;
        }
    }

    @Path("listener.motd")
    @Comment("The Motd which will be displayed in the server tab of a player and returned during ping")
    private String motd = "§bWaterbucket§3PE";

    @Path("listener.sub_motd")
    @Comment("The secondary MOTD shown in the server list and returned during ping")
    private String subMotd = "WaterbucketPE Proxy";

    @Path("listener.name")
    @Comment("The name that is shown up in the player list (pause menu)")
    private String name = "WaterBucketPE";

    @Path("listener.priorities")
    @Comment("The server priority list. If not changed by plugins, the proxy will connect the player to the first of those servers")
    private List<String> priorities = new ArrayList<>(Collections.singletonList("lobby1"));

    @Path("listener.host")
    @Comment("The address to bind the server to")
    private InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", 19132);

    @Path("listener.transport")
    @Comments({
            "How players connect, which takes a restart to change:",
            "nethernet: the connection method of current Bedrock Edition, set up in the nethernet section",
            "raknet: the old method, which Bedrock Edition will stop supporting. Query runs on it",
            "both: either way, each on its own UDP port, the listener's and nethernet.webrtc_port"
    })
    private Transport transport = Transport.BOTH;

    @Path("listener.max_players")
    @Comment("The maximum amount of players that can connect to this proxy instance")
    private int maxPlayerCount = 20;

    @Path("listener.show_max_players")
    @Accessors(fluent = true)
    @Comment("If disabled, the server list shows the current player count as the maximum, so the proxy never looks full")
    private boolean showMaxPlayers = true;

    @Path("listener.ping_passthrough")
    @Comments({
            "Pass the priority server's real server-list ping through to clients (Velocity-style).",
            "disabled: the proxy answers with its own motd/sub_motd (default).",
            "description: the backend's motd/sub_motd replace the proxy's, player counts stay live.",
            "all: description plus the backend's player counts and game type.",
            "The backend pong is refreshed in the background; while it is unavailable the proxy falls back to its own motd."
    })
    private PingPassthrough pingPassthrough = PingPassthrough.DISABLED;

    @Path("listener.command_rate_limit")
    @Comments({
            "Maximum commands a player may run per second; extra ones are dropped with a warning",
            "message (Velocity-style flood protection). Set 0 to disable."
    })
    private int commandRateLimit = 20;

    @Path("listener.forced_hosts")
    @Comments({
            "Map the ip a player joined through to a specific server",
            "for example skywars.xyz.com => SkyWars-1",
            "when a player connects using skywars-xyz.com as the serverIp, he will be connected to SkyWars-1 directly"
    })
    private Map<String, String> forcedHosts = new HashMap<>();

    @Path("servers")
    @Comments({
            "A list of all downstream servers that are available right after starting",
            "address field is formatted using ip:port",
            "publicAddress is optional and can be set to the ip players can directly connect through"
    })
    private ServerList serverList = new ServerList().initEmpty();

    @Path("network_settings")
    @Comment("Connection and security related settings. Do NOT edit unless you know what you are doing!")
    private NetworkSettings networkSettings = new NetworkSettings();

    @Path("nethernet")
    @Comment("NetherNet settings, used when listener.transport is nethernet or both")
    private NetherNetSettings netherNetSettings = new NetherNetSettings();

    @Path("only_proxy_join")
    @Comments({
            "Only Proxy Join: when enabled, the proxy keeps host firewall rules that block direct",
            "public access to every downstream server's UDP port (loopback stays allowed), so players",
            "can only join through the proxy port below. Applying needs admin/root once; disable or",
            "shut the proxy down to remove the rules automatically."
    })
    private OnlyProxyJoinSettings onlyProxyJoinSettings = new OnlyProxyJoinSettings();

    @Path("persian_fix")
    @Comments({
            "Persian Fix: display-ready Persian (and every other Arabic-script language) on the",
            "client - joined letters and right-to-left order, exactly like the well-known Persian",
            "server plugin, but done once in the proxy so it works with every downstream server",
            "software. Off by default; the areas below pick where the fix applies."
    })
    private PersianFixSettings persianFixSettings = new PersianFixSettings();

    @Path("world_view")
    @Comments({
            "World View: the proxy watches the real worlds of the downstream servers - chunks,",
            "blocks, block entities, entities, players and inventories - through the embedded Go",
            "runtime. Plugins read live world data and react to block, entity, world and",
            "inventory events with real cancel semantics."
    })
    private WorldViewSettings worldViewSettings = new WorldViewSettings();


    @Path("permissions")
    @Comment("Case-Sensitive permission list for players (empty using {})")
    // Empty by default: shipping permissions for specific usernames let anyone registering
    // those names inherit transfer/list permissions on a fresh install.
    private Object2ObjectOpenHashMap<String, List<String>> playerPermissions = new Object2ObjectOpenHashMap<>();

    @Path("permissions_default")
    @Comment("List of permissions each player should get by default (empty using [])")
    private List<String> defaultPermissions = new ArrayList<>(Arrays.asList("waterbucket.command.help", "waterbucket.command.info", "waterbucket.command.me"));

    @Path("enable_debug")
    @Comment("Whether the debug output in the console should be enabled or not")
    private boolean debug;

    @Path("upstream_encryption")
    @Comment("If enabled, encrypted connection between client and proxy will be created")
    private boolean upstreamEncryption = true;

    @Path("online_mode")
    @Comment("If enabled, only players which are authenticated with XBOX Live can join. If disabled, anyone can connect *with any name*")
    private boolean onlineMode = true;

    @Path("listener.additional_ports")
    @Comment("Additional ports to listen to")
    private List<Integer> additionalPorts = new ArrayList<>();

    @Path("listener.join_handler")
    @Comment("The join handler used to determine the first entered server. Possible options: DefaultJoinHandler")
    private String joinHandler = "DefaultJoinHandler";

    @Path("listener.reconnect_handler")
    @Comment("The reconnect handler used to determine fallback connections. Possible options: DefaultReconnectHandler, RoundRobinReconnectHandler")
    private String reconnectHandler = "DefaultReconnectHandler";

    @Path("use_login_extras")
    @Accessors(fluent = true)
    @Comment("If enabled, the proxy passes extra login fields such as the XUID and Minecraft ID to the downstream server using custom fields in the LoginPacket. The player's real IP is forwarded separately by forward_client_ip")
    private boolean useLoginExtras = false;

    @Path("forward_client_ip")
    @Accessors(fluent = true)
    @Comment("If enabled, the proxy embeds the player's real IP and port into the login data sent to every downstream server, so servers stop logging joins from the proxy's own socket (e.g. 127.0.0.1 with a random port) and show the player's real address. Only enable this for server software that actually reads the forwarding fields (Nukkit-MOT, Geyser, or a login-extras plugin): strict servers such as PocketMine-MP treat the extra login data fields as unknown client data properties and reject the whole login as a bad packet. Off by default so every backend software accepts logins out of the box")
    private boolean forwardClientIp = false;

    @Path("replace_username_spaces")
    @Comment("Replaces username spaces with underscores if enabled")
    private boolean replaceUsernameSpaces = false;

    @Path("kick_existing_players")
    @Accessors(fluent = true)
    @Comment("Velocity-style duplicate login handling: when enabled, an older session with the same display name is disconnected (with a notice) so the newcomer takes over. When disabled, both sessions stay online and the newcomer only claims the name lookup")
    private boolean kickExistingPlayers = false;

    @Path("enable_query")
    @Accessors(fluent = true)
    @Comment("Whether server query should be enabled")
    private boolean enableQuery = true;

    @Path("prefer_fast_transfer")
    @Accessors(fluent = true)
    @Comment("If enabled, when receiving a McpeTransferPacket, the proxy will check if the target server is in the downstream list, and if yes, use the fast transfer mechanism")
    private boolean useFastTransfer = true;

    @Path("inject_proxy_commands")
    @Accessors(fluent = true)
    @Comment("If enabled, the proxy will inject all the proxy commands in the AvailableCommandsPacket, enabling autocompletion")
    private boolean injectCommands = true;

    @Path("compression")
    @Comments({
            "Algorithm used for upstream compression. Currently supported: zlib, snappy",
            "This is only applicable on 1.19.30 and newer versions"
    })
    private CompressionType compression = CompressionType.ZLIB;

    @Path("upstream_compression_level")
    @Comment("Upstream server compression ratio(proxy to client), higher = less bandwidth, more cpu, lower vice versa")
    private int upstreamCompression = 6;

    @Path("downstream_compression_level")
    @Comment("Downstream server compression ratio(proxy to downstream server), higher = less bandwidth, more cpu, lower vice versa")
    private int downstreamCompression = 2;

    @Path("enable_edu_features")
    @Accessors(fluent = true)
    @Comment("Education features require small adjustments to work correctly. Enable this option if any of downstream servers support education features.")
    private boolean enableEducationFeatures = true;

    @Path("enable_packs")
    @Accessors(fluent = true)
    @Comment("Enable/Disable the resource pack system")
    private boolean enableResourcePacks = true;

    @Path("overwrite_client_packs")
    @Comment("If this is enabled, the client will not be able to use custom packs")
    private boolean overwriteClientPacks = false;

    @Path("force_server_packs")
    @Comment("If enabled, the client will be forced to accept server-sided resource packs")
    private boolean forceServerPacks = false;

    @Path("pack_cache_size")
    @Comment("You can set maximum pack size in MB to be cached.")
    private int packCacheSize = 16;

    @Path("pack_cdn_urls")
    @Comments({
            "Resource packs which should be delivered to clients using a CDN URL instead of the in-protocol chunked transfer.",
            "Each URL must point directly to the pack zip file and be publicly reachable over HTTPS.",
            "Packs are downloaded once at startup to determine their manifest, size and hash, and remain available",
            "via the chunked transfer as fallback for clients which can not download from the CDN."
    })
    private List<String> packCdnUrls = new ArrayList<>();

    @Path("disable_cdn_for")
    @Comments({
            "Device platforms listed here never receive CDN URLs and instead get packs through the chunked transfer.",
            "Example: [ANDROID, IOS]"
    })
    private List<String> disableCdnPlatforms = new ArrayList<>();

    @Path("force_disable_vibrant_visuals")
    @Comment("If enabled, clients which support Vibrant Visuals (deferred lighting) will be forced to turn it off.")
    private boolean forceDisableVibrantVisuals = false;

    @Path("enable_panel")
    @Accessors(fluent = true)
    @Comments({
            "Local web panel: a live dashboard with players, downstream servers, history charts",
            "and a console served by the proxy itself at http://<address>:<port>.",
            "It binds to the loopback interface by default; open it to other interfaces only",
            "together with a reverse proxy that provides TLS."
    })
    private boolean enablePanel = true;

    @Path("panel_bind_address")
    @Accessors(fluent = true)
    @Comment("Address the web panel listens on. Keep 127.0.0.1 unless you know what you are doing")
    private String panelBindAddress = "127.0.0.1";

    @Path("panel_port")
    @Accessors(fluent = true)
    @Comment("Port of the web panel. Set to 0 to pick a free port automatically at startup")
    private int panelPort = 8080;

    @Path("panel_token")
    @Accessors(fluent = true)
    @Comments({
            "Access token for the web panel API. Leave empty to auto-generate one on first start;",
            "the generated token is stored in panel_token.txt next to the config (never logged)."
    })
    private String panelToken = "";

    @Path("default_idle_threads")
    @Comment("Creating threads may be in some situations expensive. Specify minimum count of idle threads per internal thread executors. Set to -1 to auto-detect by core count.")
    private int defaultIdleThreads = -1;

    @Path("enable_statistics")
    @Comment("Enable anonymous statistics that are sent to bstats. For more information, check out our bstats page at https://bstats.org/plugin/server-implementation/WaterdogPE/15678")
    private boolean enableAnonymousStatistics = true;

    @Path("enable_error_reporting")
    @Comment("Enables anonymous error reporting using bugsnag. This allows the WaterbucketPE team to automatically collect issues occurring on WaterbucketPE instances.")
    private boolean enableAnonymousErrorReporting = true;

    @Path("restart.use_script")
    @Accessors(fluent = true)
    @Comments({
            "When /restart runs, generate (if missing) and execute a relaunch script that waits",
            "for the proxy process to exit and then starts a new instance with the same JVM options,",
            "so new plugin jars, packs and config values are picked up. When disabled, /restart",
            "behaves like a plain graceful shutdown and your wrapper/service manager must relaunch."
    })
    private boolean restartUseScript = true;

    @Path("restart.generate_script")
    @Accessors(fluent = true)
    @Comment("Whether a missing restart script may be auto-generated next to the config at restart time")
    private boolean restartGenerateScript = true;

    @Path("restart.script_path")
    @Accessors(fluent = true)
    @Comment("Custom restart script path (relative paths resolve against the proxy folder). Empty = restart.sh / restart.bat")
    private String restartScriptPath = "";

    @Path("config_version")
    @Comment("The layout of this file, used to upgrade it. Do not change it")
    @Setter(AccessLevel.NONE)
    private int configVersion = ConfigMigrations.CURRENT;

    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean deferWrites;
    /** The file is behind what was loaded from it and has to be written back. */
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient boolean outdated;

    public ProxyConfig(File file) {
        this.CONFIG_HEADER = new String[]{"Waterbucket Main Configuration file", "Configure your desired network settings here."};
        this.CONFIG_FILE = file;
        try {
            this.addConverter(InetSocketAddressConverter.class);
            this.addConverter(ServerEntryConverter.class);
            this.addConverter(ServerListConverter.class);
            this.addConverter(CompressionAlgorithmConverter.class);
            this.addConverter(LowercaseEnumConverter.class);
        } catch (InvalidConverterException e) {
            ProxyServer.getInstance().getLogger().error("Error while initiating config converters", e);
        }
    }

    @Override
    public void save(boolean withComments) throws InvalidConfigurationException {
        // Yamler gathers the comments on every save without dropping the last ones, so a second save
        // of this object would repeat them all
        this.clearComments();
        if (withComments) {
            SettingsSection.describeSections(this);
        }
        super.save(withComments);
    }

    @Override
    protected void loadFromYaml() throws InvalidConfigurationException {
        super.loadFromYaml();
        // Clamp the zlib levels into the range Deflater accepts: an out-of-range value used to
        // escape as a bare IllegalArgumentException and abort the boot (or every transfer).
        this.upstreamCompression = clampCompressionLevel("upstream_compression_level", this.upstreamCompression);
        this.downstreamCompression = clampCompressionLevel("downstream_compression_level", this.downstreamCompression);

        // A file from before config_version existed is 0. A quoted "2" (string) counts as 2:
        // hand-edited files used to fail the instanceof-Number check, silently re-running every
        // migration step and flagging the file outdated on every boot.
        int version = 0;
        Object rawVersion = this.root.get("config_version");
        if (rawVersion instanceof Number number) {
            version = number.intValue();
        } else if (rawVersion instanceof String text && text.matches("\\d+")) {
            version = Integer.parseInt(text);
        }
        if (version < 0) {
            throw new InvalidConfigurationException(this.CONFIG_FILE + " has an invalid config_version " + version);
        }
        if (version > ConfigMigrations.CURRENT) {
            throw new InvalidConfigurationException(this.CONFIG_FILE + " is from a newer WaterbucketPE and is not compatible "
                    + "(config_version " + version + ", this version reads up to " + ConfigMigrations.CURRENT
                    + "). Update WaterbucketPE or restore a backup of the file");
        }
        if (version < ConfigMigrations.CURRENT) {
            ConfigMigrations.migrate(this.root, version);
            // Filled in again from the field, which puts it last like in a new file
            this.root.remove("config_version");
            this.outdated = true;
        }
    }

    @Override
    public void load() throws InvalidConfigurationException {
        // Yamler writes the file back by itself only when a key is missing at the root, and then
        // without comments, so its write is held back and done here for sections as well
        this.deferWrites = true;
        try {
            super.load();
        } finally {
            this.deferWrites = false;
        }
        if (this.outdated || SettingsSection.upgradedSections(this)) {
            this.outdated = false;
            this.save();
        }
    }

    @Override
    protected void saveToYaml() throws InvalidConfigurationException {
        if (this.deferWrites) {
            this.outdated = true;
            return;
        }
        // The file may come from a read-only mount, such as a Kubernetes ConfigMap. Options it does
        // not carry keep their defaults instead of failing the startup
        if (this.CONFIG_FILE.exists() && !this.CONFIG_FILE.canWrite()) {
            ProxyServer.getInstance().getLogger().warning("Config file " + this.CONFIG_FILE + " is not writable, so it is not brought up to date: options "
                    + "missing from it keep their defaults, and an older layout is migrated again on every start");
            return;
        }
        super.saveToYaml();
    }

    public int getIdleThreads() {
        return this.defaultIdleThreads < 1 ? Runtime.getRuntime().availableProcessors() : this.defaultIdleThreads;
    }

    private static int clampCompressionLevel(String key, int level) {
        if (level < 0 || level > 9) {
            ProxyServer.getInstance().getLogger().warning("Config value '" + key + "' is " + level
                    + ", but zlib levels are 0-9; using " + Math.max(0, Math.min(9, level)));
            return Math.max(0, Math.min(9, level));
        }
        return level;
    }
}
