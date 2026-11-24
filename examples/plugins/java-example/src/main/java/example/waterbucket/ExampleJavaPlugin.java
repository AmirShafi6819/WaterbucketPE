package example.waterbucket;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.Subscribe;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ConnectionRequest;
import dev.waterbucket.waterbucketpe.player.ConnectionResult;
import dev.waterbucket.waterbucketpe.player.ConnectionResultStatus;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.scheduler.ScheduledTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A tour of the WaterbucketPE plugin API: lifecycle hooks, functional and annotation events,
 * commands, tick and time-based scheduling, connection requests with real results, the
 * proxy-level player/server/max-players API and broadcasting.
 *
 * The descriptor lives in waterbucket.yml. Alternatively a jar can skip the YAML entirely
 * and declare itself with @PluginDescriptor on this class (Velocity style):
 *
 *   @PluginDescriptor(id = "example-java", name = "ExampleJava", version = "1.0.0",
 *                     authors = {"WaterbucketPE"}, dependencies = {})
 */
public final class ExampleJavaPlugin extends Plugin {

    @Override
    public void onStartup() {
        getLogger().info("ExampleJava loaded");
    }

    @Override
    public void onEnable() {
        // --- Events (functional style) ----------------------------------------------

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

        // --- Events (annotation style, Velocity-like) --------------------------------

        // registerListener detaches every @Subscribe method automatically when this plugin
        // disables - no cleanup needed in onDisable().
        getProxy().getEventManager().registerListener(this, new JoinAnnouncer());

        // --- Proxy API ------------------------------------------------------------

        // Runtime max players: persisted to config.yml, enforced at login and used in
        // ping/query. Players with waterbucket.bypass.maxplayers are exempt.
        getProxy().setMaxPlayers(100);

        // Player lookups; getAllPlayers() is the Velocity-style alias for getPlayers().values()
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
                    ProxiedPlayer player = (ProxiedPlayer) sender;
                    // Velocity-style connection request: the future reports the real outcome
                    // instead of having to infer it from events.
                    ConnectionRequest request = player.createConnectionRequest(hub);
                    request.connectWithIndication().thenAccept((ConnectionResult result) -> {
                        if (result.getStatus() == ConnectionResultStatus.SUCCESS) {
                            player.sendMessage("§aMoving you to " + hub.getServerName());
                        } else {
                            player.sendMessage("§7Could not move you: " + result.getStatus());
                        }
                    });
                }
                return true;
            }
        };

        if (!getProxy().getCommandMap().registerCommand(command)) {
            getLogger().warn("Could not register /javahello; the name is already in use");
        }

        // --- Scheduling (tick based) -------------------------------------------------

        // Plugin-owned tasks are cancelled automatically when this plugin disables
        getProxy().getScheduler().scheduleRepeating(this, () ->
                getLogger().debug("{} player(s) online", getProxy().getAllPlayers().size()), 20, 100);

        // --- Scheduling (real time, Velocity-style TaskBuilder) ----------------------

        // Runs on a dedicated time-scheduler pool; the cadence is independent of the tick
        // loop and repeats use fixed-delay semantics (no overlap when a run is slow).
        ScheduledTask motdRefresher = getProxy().getScheduler()
                .buildTask(this, () -> getLogger().debug("motd refresher tick"))
                .delay(10, TimeUnit.SECONDS)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();
    }

    @Override
    public void onDisable() {
        // Scheduled tasks (tick and time based) and @Subscribe listeners registered for this
        // plugin are already detached by the proxy at this point.
        getLogger().info("ExampleJava disabled");
    }

    /**
     * Annotation listeners: public methods with exactly one Event parameter. ignoreCancelled
     * skips the method when an earlier handler already cancelled the event.
     */
    public static final class JoinAnnouncer {

        @Subscribe(priority = EventPriority.LOW)
        public void onChat(PlayerChatEvent event) {
            if (event.getMessage().contains("waterbucket")) {
                event.getPlayer().sendMessage("§bYou mentioned the good word.");
            }
        }
    }
}
