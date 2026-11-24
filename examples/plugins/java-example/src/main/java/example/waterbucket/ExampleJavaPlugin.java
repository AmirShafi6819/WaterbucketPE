package example.waterbucket;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;

import java.util.List;

/**
 * A tour of the WaterbucketPE plugin API: lifecycle hooks, events, commands, scheduling,
 * the proxy-level player/server/max-players API and broadcasting.
 */
public final class ExampleJavaPlugin extends Plugin {

    @Override
    public void onStartup() {
        getLogger().info("ExampleJava loaded");
    }

    @Override
    public void onEnable() {
        // --- Events ---------------------------------------------------------------

        // Observe chat at NORMAL priority
        getProxy().getEventManager().subscribe(
                PlayerChatEvent.class,
                event -> getLogger().info(
                        "{}: {}", event.getPlayer().getName(), event.getMessage()
                ),
                EventPriority.NORMAL
        );

        // Rewrite or block any command a player types, before the proxy or the
        // downstream server handles it
        getProxy().getEventManager().subscribe(PlayerCommandEvent.class, event -> {
            if (event.getCommand().equalsIgnoreCase("/greed")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThat command is disabled.");
            }
        });

        // Customize the server-list ping
        getProxy().getEventManager().subscribe(ProxyPingEvent.class, event -> {
            event.setMotd("§bWaterbucket§3PE");
            event.setSubMotd("Powered by ExampleJava");
        });

        // --- Proxy API ------------------------------------------------------------

        // Runtime max players: persisted to config.yml, enforced at login and used in
        // ping/query. Players with waterbucket.bypass.maxplayers are exempt.
        getProxy().setMaxPlayers(100);

        // Player lookups
        ProxiedPlayer exact = getProxy().getPlayerExact("Steve");
        List<ProxiedPlayer> partial = getProxy().matchPlayer("ste");

        // Register a downstream server at runtime
        ServerInfo hub = getProxy().getServerInfo("lobby1");

        // Broadcast to every player plus the console
        getProxy().broadcastMessage("§aExampleJava enabled §7(the proxy now reports 100 slots)");

        // --- Commands ---------------------------------------------------------------

        Command command = new Command(
                "javahello",
                CommandSettings.builder()
                        .setDescription("Example command from a Java plugin")
                        .setUsageMessage("/javahello")
                        .build()
        ) {
            @Override
            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                sender.sendMessage("Hello from Java!");
                if (sender.isPlayer()) {
                    // Send a player to the first configured priority server
                    ProxiedPlayer player = (ProxiedPlayer) sender;
                    String firstPriority = getProxy().getConfiguration().getPriorities().get(0);
                    ServerInfo target = getProxy().getServerInfo(firstPriority);
                    if (target != null) {
                        player.connect(target);
                    }
                }
                return true;
            }
        };

        if (!getProxy().getCommandMap().registerCommand(command)) {
            getLogger().warn("Could not register /javahello; the name is already in use");
        }

        // --- Scheduling --------------------------------------------------------------

        // Plugin-owned tasks are cancelled automatically when this plugin disables
        getProxy().getScheduler().scheduleRepeating(this, () ->
                getLogger().debug("{} player(s) online", getProxy().getPlayers().size()), 20, 100);
    }

    @Override
    public void onDisable() {
        getLogger().info("ExampleJava disabled; its scheduled tasks were cancelled automatically");
    }
}
