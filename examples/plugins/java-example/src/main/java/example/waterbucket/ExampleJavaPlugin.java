package example.waterbucket;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.plugin.Plugin;

public final class ExampleJavaPlugin extends Plugin {

    @Override
    public void onStartup() {
        getLogger().info("ExampleJava loaded");
    }

    @Override
    public void onEnable() {
        getProxy().getEventManager().subscribe(
                PlayerChatEvent.class,
                event -> getLogger().info(
                        "{}: {}", event.getPlayer().getName(), event.getMessage()
                ),
                EventPriority.NORMAL
        );

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
                return true;
            }
        };

        if (!getProxy().getCommandMap().registerCommand(command)) {
            getLogger().warn("Could not register /javahello; the name is already in use");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("ExampleJava disabled");
    }
}
