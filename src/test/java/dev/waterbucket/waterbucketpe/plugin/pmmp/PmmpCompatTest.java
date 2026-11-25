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

package dev.waterbucket.waterbucketpe.plugin.pmmp;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.ConsoleCommandSender;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerDisconnectedEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerLoginEvent;
import dev.waterbucket.waterbucketpe.player.PlayerManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.PluginManager;
import dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerJoinEvent;
import dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerQuitEvent;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the compatibility plugin surface: the Server/Player facades, the
 * compatibility events over the real proxy event pipeline (cancellation and edits included),
 * the Config utility, the scheduler facade and the rebuilt economy plugin running through all
 * of it - the same calls a plugin of the original server software makes.
 */
public class PmmpCompatTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private PlayerManager playerManager;
    private CommandMap commandMap;
    private ProxiedPlayer rawPlayer;
    private final List<String> broadcastLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        this.playerManager = mock(PlayerManager.class);
        this.commandMap = mock(CommandMap.class);
        PluginManager pluginManager = mock(PluginManager.class);
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);

        when(this.proxy.getConfiguration()).thenReturn(config);
        when(config.getMotd()).thenReturn("§bWaterbucket§3PE");
        when(config.getSubMotd()).thenReturn("WaterbucketPE Proxy");
        when(config.getMaxPlayerCount()).thenReturn(20);
        when(config.getBindAddress()).thenReturn(new java.net.InetSocketAddress("0.0.0.0", 19132));
        when(this.proxy.getPlayerManager()).thenReturn(this.playerManager);
        when(this.proxy.getCommandMap()).thenReturn(this.commandMap);
        when(this.proxy.getPluginManager()).thenReturn(pluginManager);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir.resolve("plugins"));
        when(this.proxy.getDataPath()).thenReturn(this.tempDir);
        when(this.proxy.getScheduler()).thenReturn(scheduler);
        when(this.proxy.isRunning()).thenReturn(true);
        // console dispatch routes through the proxy
        when(this.proxy.dispatchCommand(any(dev.waterbucket.waterbucketpe.command.CommandSender.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1, String.class).startsWith("say"));

        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        // The bridge under test registers itself and initializes the Server facade
        PmmpEventBridge.register(this.proxy);
        assertTrue(Server.isAvailable(), "the bridge must initialize the Server facade");

        UUID playerUuid = UUID.randomUUID();
        this.rawPlayer = this.mockPlayer("TestPlayer", playerUuid);
        when(this.playerManager.getPlayerCount()).thenReturn(1);
        when(this.playerManager.getPlayers()).thenReturn(Map.of(playerUuid, this.rawPlayer));
        // the real registry is case-insensitive; mirror that in the mock
        when(this.playerManager.getPlayer(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0, String.class);
            return "TestPlayer".equalsIgnoreCase(name) ? this.rawPlayer : null;
        });
        when(this.playerManager.matchPlayer(anyString())).thenReturn(this.rawPlayer);
    }

    private ProxiedPlayer mockPlayer(String name, UUID uuid) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getXuid()).thenReturn("1234567890abcdef");
        when(player.getAddress()).thenReturn(new java.net.InetSocketAddress("203.0.113.9", 50000));
        when(player.isConnected()).thenReturn(true);
        when(player.getServerInfo()).thenReturn(null);
        // every broadcast lands in the capture list
        org.mockito.Mockito.doAnswer(invocation -> {
            this.broadcastLines.add(String.valueOf((Object) invocation.getArgument(0)));
            return null;
        }).when(player).sendMessage(anyString());
        return player;
    }

    @AfterEach
    void tearDown() {
        // nothing to reset: the Server singleton is re-initialized per test by the bridge
    }

    // ---- Server facade ----

    @Test
    public void serverFacadeExposesTheLiveProxyState() {
        Server server = Server.getInstance();
        assertEquals("WaterbucketPE", server.getName());
        assertEquals("19132", String.valueOf(server.getPort()));
        assertEquals(20, server.getMaxPlayers());
        assertEquals(1, server.getOnlineCount());
        assertTrue(server.isRunning());
        assertEquals("§bWaterbucket§3PE", server.getMotd());

        server.setMotd("§aNew Motd");
        verify(this.proxy.getConfiguration()).setMotd("§aNew Motd");

        assertEquals(1, server.getOnlinePlayers().size());
        assertEquals("TestPlayer", server.getPlayer("TESTPLAYER").getName());
        assertNull(server.getPlayer("Nobody"));
        assertEquals("TestPlayer", server.matchPlayer("Test").getName());
    }

    @Test
    public void broadcastReachesEveryOnlinePlayer() {
        Server.getInstance().broadcastMessage("§eHello everyone");
        assertEquals(List.of("§eHello everyone"), this.broadcastLines);

        Server.getInstance().broadcastTip("tip");
        Server.getInstance().broadcastPopup("popup");
        verify(this.rawPlayer).sendTip("tip");
        verify(this.rawPlayer).sendPopup("popup", "");
    }

    @Test
    public void consoleCommandsDispatchThroughTheProxy() {
        assertTrue(Server.getInstance().dispatchCommand("say hi"));
        verify(this.proxy).dispatchCommand(any(ConsoleCommandSender.class), eq("say hi"));
        assertFalse(Server.getInstance().dispatchCommand("unknown"));
    }

    // ---- Player wrapper ----

    @Test
    public void playerWrapperExposesTheRealIdentity() {
        Player player = Server.getInstance().getPlayer("TestPlayer");
        assertEquals("TestPlayer", player.getName());
        assertEquals("testplayer", player.getLowerCaseName());
        assertEquals("1234567890abcdef", player.getXuid());
        assertEquals(this.rawPlayer.getUniqueId(), player.getUniqueId());
        assertEquals("203.0.113.9", player.getAddress());
        assertEquals(50000, player.getPort());
        assertTrue(player.isConnected());
        assertTrue(player.isOnline());
    }

    @Test
    public void playerMessagingMethodsAreReal() {
        Player player = Server.getInstance().getPlayer("TestPlayer");
        player.sendMessage("Hello {%0}!", "World");
        verify(this.rawPlayer).sendMessage("Hello World!");

        player.sendTip("tip text");
        player.sendPopup("popup text");
        player.sendTitle("Main", "Sub", 1, 2, 3);
        player.sendActionBar("action bar");
        player.sendToastMessage("Toast", "Body");

        verify(this.rawPlayer).sendTip("tip text");
        verify(this.rawPlayer).sendPopup("popup text", "");
        verify(this.rawPlayer).sendTitle("Main", "Sub", 1, 2, 3);
        verify(this.rawPlayer).sendPacket(any(org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket.class));
        verify(this.rawPlayer).sendToastMessage("Toast", "Body");
    }

    @Test
    public void playerChatSendsThroughTheProxySession() {
        Player player = Server.getInstance().getPlayer("TestPlayer");
        player.chat("hello world");
        verify(this.rawPlayer).chat("hello world");
    }

    @Test
    public void formIdsAreAllocatedAndFormsSent() {
        Player player = Server.getInstance().getPlayer("TestPlayer");
        int id1 = player.sendFormJson("{\"type\":\"form\",\"title\":\"Hi\"}");
        int id2 = player.sendFormJson("{\"type\":\"modal\"}", id1 + 7);
        verify(this.rawPlayer, org.mockito.Mockito.times(2))
                .sendPacket(any(org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket.class));
        assertTrue(id1 >= 100000);
        assertEquals(id1 + 7, id2);
    }

    @Test
    public void kickFiresItsEventAndDisconnects() {
        Player player = Server.getInstance().getPlayer("TestPlayer");
        assertTrue(player.kick("§cBanned!"));
        verify(this.rawPlayer).disconnect("§cBanned!");
        // the quit message of the kick event was broadcast like a leave
        assertTrue(this.broadcastLines.stream().anyMatch(line -> line.contains("left the game")));
    }

    @Test
    public void cancelledKickKeepsThePlayerOnline() {
        // a plugin vetoes every kick of this name
        this.eventManager.subscribe(dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerKickEvent.class,
                event -> event.setCancelled());
        Player player = Server.getInstance().getPlayer("TestPlayer");
        assertFalse(player.kick("kick attempt"));
        verify(this.rawPlayer, never()).disconnect(anyString());
    }

    // ---- compatibility events over the real pipeline ----

    @Test
    public void joinEventFiresOnLoginWithTheDefaultMessage() {
        List<PlayerJoinEvent> joins = new CopyOnWriteArrayList<>();
        this.eventManager.subscribe(PlayerJoinEvent.class, joins::add);

        PlayerLoginEvent login = new PlayerLoginEvent(this.rawPlayer);
        this.eventManager.callEvent(login).join();

        assertEquals(1, joins.size());
        assertEquals("TestPlayer", joins.get(0).getPlayer().getName());
        assertEquals("§eTestPlayer joined the game", joins.get(0).getJoinMessage());
        // the join message was broadcast, exactly like the original software
        assertTrue(this.broadcastLines.contains("§eTestPlayer joined the game"));
    }

    @Test
    public void cancelledCompatLoginCancelsTheProxyLogin() {
        this.eventManager.subscribe(dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerLoginEvent.class,
                event -> {
                    event.setCancelled();
                    event.setKickMessage("§cMaintenance");
                });

        PlayerLoginEvent login = new PlayerLoginEvent(this.rawPlayer);
        this.eventManager.callEvent(login).join();

        assertTrue(login.isCancelled());
        assertEquals("§cMaintenance", login.getCancelReason());
    }

    @Test
    public void chatEventEditsAndCancellationsPropagate() {
        // edit
        this.eventManager.subscribe(dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent.class,
                event -> event.setMessage(event.getMessage().replace("bad", "good")));
        PlayerChatEvent chat = new PlayerChatEvent(this.rawPlayer, "this is bad text");
        this.eventManager.callEvent(chat);
        assertFalse(chat.isCancelled());
        assertEquals("this is good text", chat.getMessage());

        // cancel
        this.eventManager.subscribe(dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent.class,
                dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent::setCancelled);
        PlayerChatEvent second = new PlayerChatEvent(this.rawPlayer, "swear words");
        this.eventManager.callEvent(second);
        assertTrue(second.isCancelled());
    }

    @Test
    public void commandPreprocessEventCarriesTheSlashLine() {
        List<String> seen = new CopyOnWriteArrayList<>();
        this.eventManager.subscribe(dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerCommandPreprocessEvent.class,
                event -> seen.add(event.getMessage()));

        PlayerCommandEvent command = new PlayerCommandEvent(this.rawPlayer, "/money");
        this.eventManager.callEvent(command);

        assertEquals(List.of("/money"), seen);
        assertFalse(command.isCancelled());
    }

    @Test
    public void quitEventFiresOnDisconnect() {
        List<PlayerQuitEvent> quits = new CopyOnWriteArrayList<>();
        this.eventManager.subscribe(PlayerQuitEvent.class, quits::add);

        PlayerDisconnectedEvent disconnect = new PlayerDisconnectedEvent(this.rawPlayer, "Disconnected");
        this.eventManager.callEvent(disconnect).join();

        assertEquals(1, quits.size());
        assertEquals("Disconnected", quits.get(0).getReason());
        assertTrue(this.broadcastLines.contains("§eTestPlayer left the game"));
    }

    // ---- Config utility ----

    @Test
    public void configSupportsNestedAccessAndPersistence() {
        Path file = this.tempDir.resolve("data").resolve("players.yml");
        Config config = new Config(file);

        assertFalse(config.exists("onebone.money"));
        config.set("onebone.money", 2500);
        config.set("steve.money", 100);
        config.set("deep.nested.list", List.of("a", "b"));
        assertTrue(config.hasChanged());
        assertTrue(config.save());

        // reload from disk and read everything back
        Config reloaded = new Config(file);
        assertEquals(2500, reloaded.getInt("onebone.money", 0));
        assertEquals(100, reloaded.getInt("steve.money", 0));
        assertEquals(List.of("a", "b"), reloaded.get("deep.nested.list"));
        assertTrue(reloaded.getBoolean("steve.flag", false) == false
                || reloaded.getBoolean("steve.flag", true) == true); // absent key -> default
        assertFalse(reloaded.getBoolean("steve.flag", false));
        assertEquals("fallback", reloaded.getString("missing.key", "fallback"));
        assertTrue(!reloaded.hasChanged());
    }

    @Test
    public void configAlsoSpeaksJsonAndRemoveWorks() {
        Path file = this.tempDir.resolve("data").resolve("settings.json");
        Config config = new Config(file, Config.Type.JSON);
        config.set("enabled", true);
        config.set("rate", 5.5);
        assertTrue(config.save());

        Config reloaded = new Config(file, Config.Type.JSON);
        assertTrue(reloaded.getBoolean("enabled", false));
        assertEquals(5.5D, reloaded.getDouble("rate", 0D), 0.0001D);

        reloaded.remove("rate");
        reloaded.save();
        Config afterRemove = new Config(file, Config.Type.JSON);
        assertTrue(afterRemove.exists("enabled"));
        assertFalse(afterRemove.exists("rate"));
    }

    // ---- Scheduler facade ----

    @Test
    public void schedulerFacadeDelegatesAndCancels() {
        Runnable task = mock(Runnable.class);
        dev.waterbucket.waterbucketpe.scheduler.TaskHandler<?> delegate =
                mock(dev.waterbucket.waterbucketpe.scheduler.TaskHandler.class);
        WaterbucketScheduler scheduler = this.proxy.getScheduler();
        org.mockito.Mockito.doReturn(delegate).when(scheduler).scheduleDelayed(task, 10, false);
        org.mockito.Mockito.doReturn(delegate).when(scheduler).scheduleRepeating(task, 5, false);

        Scheduler compat = Server.getInstance().getScheduler();
        TaskHandler delayed = compat.scheduleDelayedTask(task, 10);
        TaskHandler repeated = compat.scheduleRepeatingTask(task, 5);

        assertTrue(delayed.getTaskId() > 0);
        assertEquals(delayed.getTaskId() + 1, repeated.getTaskId());
        assertTrue(delayed.cancel());
        verify(delegate).cancel();
        assertEquals(0, compat.getCurrentTick());
    }

    @Test
    public void schedulerAttributedOverloadsPassThePlugin() {
        PluginManager pluginManager = mock(PluginManager.class);
        WaterbucketScheduler scheduler = this.proxy.getScheduler();
        dev.waterbucket.waterbucketpe.plugin.Plugin plugin = mock(dev.waterbucket.waterbucketpe.plugin.Plugin.class);
        Runnable task = mock(Runnable.class);
        when(scheduler.scheduleTask(plugin, task, false))
                .thenReturn(mock(dev.waterbucket.waterbucketpe.scheduler.TaskHandler.class));
        when(scheduler.scheduleRepeating(eq(plugin), eq(task), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(mock(dev.waterbucket.waterbucketpe.scheduler.TaskHandler.class));

        Server.getInstance().getScheduler().scheduleTask(plugin, task, false);
        Server.getInstance().getScheduler().scheduleRepeatingTask(plugin, task, 20, false);
        verify(scheduler).scheduleTask(plugin, task, false);
        verify(scheduler).scheduleRepeating(plugin, task, 0, 20, false);
    }

    @Test
    public void pluginManagerFacadeReportsState() {
        dev.waterbucket.waterbucketpe.plugin.Plugin plugin =
                mock(dev.waterbucket.waterbucketpe.plugin.Plugin.class);
        when(plugin.getName()).thenReturn("EconomyAPI");
        when(plugin.isEnabled()).thenReturn(true);
        when(this.proxy.getPluginManager().getPlugins()).thenReturn(List.of(plugin));
        when(this.proxy.getPluginManager().getPluginByName("EconomyAPI")).thenReturn(plugin);

        PluginManagerCompat manager = Server.getInstance().getPluginManager();
        assertEquals("EconomyAPI", manager.getPlugin("economyapi").getName());
        assertTrue(manager.isPluginEnabled("EconomyAPI"));
        assertEquals(List.of("EconomyAPI"), manager.getPluginNames());
    }

    // ---- the rebuilt economy plugin ----

    @Test
    public void economyPluginRunsEndToEndThroughTheCompatibilitySurface() throws Exception {
        // a real plugin folder with a real config file
        File dataFolder = this.tempDir.resolve("plugins").resolve("EconomyAPI").toFile();
        dev.waterbucket.waterbucketpe.plugin.PluginYAML descriptor = new dev.waterbucket.waterbucketpe.plugin.PluginYAML();
        descriptor.name = "EconomyAPI";
        descriptor.version = "1.0.0";
        descriptor.author = "Test";
        descriptor.main = "EconomyApiPlugin.java";

        EconomyApiPlugin economy = new EconomyApiPlugin();
        economy.initBridge(descriptor, this.proxy, dataFolder);

        List<Command> registered = new CopyOnWriteArrayList<>();
        when(this.commandMap.registerCommand(any(Command.class))).thenAnswer(invocation -> {
            registered.add(invocation.getArgument(0));
            return true;
        });

        economy.setEnabled(true);

        // three commands registered
        assertEquals(3, registered.size());

        // a player joins: the account is created automatically through the join event
        PlayerLoginEvent login = new PlayerLoginEvent(this.rawPlayer);
        this.eventManager.callEvent(login).join();
        assertTrue(economy.existsAccount("TestPlayer"));
        assertEquals(EconomyApiPlugin.DEFAULT_MONEY, economy.getMoney("testplayer"));

        // the economy surface works: set, add, reduce, pay, failure cases
        assertTrue(economy.setMoney("TestPlayer", 500));
        assertTrue(economy.addMoney("testplayer", 250)); // case-insensitive keys
        assertEquals(750, economy.getMoney("TestPlayer"), 0.0001);
        assertTrue(economy.reduceMoney("TestPlayer", 100));
        assertEquals(650, economy.getMoney("TestPlayer"), 0.0001);

        assertTrue(economy.pay("TestPlayer", "OtherPlayer", 200));
        assertEquals(450, economy.getMoney("TestPlayer"), 0.0001);
        assertEquals(EconomyApiPlugin.DEFAULT_MONEY + 200, economy.getMoney("OtherPlayer"), 0.0001);

        assertFalse(economy.pay("TestPlayer", "OtherPlayer", 100000), "overdraft must be rejected");
        assertFalse(economy.pay("TestPlayer", "TestPlayer", 10), "self pay must be rejected");
        assertFalse(economy.pay("TestPlayer", "OtherPlayer", -5), "negative pay must be rejected");
        assertFalse(economy.setMoney("TestPlayer", -1), "negative set must be rejected");

        // balances survive a reload through the persisted file
        economy.saveAll();
        EconomyApiPlugin fresh = new EconomyApiPlugin();
        fresh.initBridge(descriptor, this.proxy, dataFolder);
        fresh.onEnable();
        assertEquals(450, fresh.getMoney("TestPlayer"), 0.0001);

        // the /money command answers with the balance
        CommandSender console = mock(CommandSender.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            this.broadcastLines.add(String.valueOf((Object) invocation.getArgument(0)));
            return null;
        }).when(console).sendMessage(anyString());
        when(console.getName()).thenReturn("Console");
        when(console.isPlayer()).thenReturn(false);

        Command moneyCommand = registered.stream()
                .filter(command -> command.getName().equals("money")).findFirst().orElseThrow();
        assertTrue(moneyCommand.onExecute(console, "money", new String[]{"TestPlayer"}));
        assertTrue(this.broadcastLines.stream().anyMatch(line -> line.contains("450")));
    }
}
