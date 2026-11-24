# WaterbucketPE Plugin Development (Java & Python)

This guide documents the plugin system implemented in WaterbucketPE 1.5.0. It covers installation, descriptors, lifecycle, events, commands, scheduling, configuration, resources, proxy API, join/fallback handlers, Java interop, compatibility, security, performance and troubleshooting.

- [فارسی](PLUGINS.fa.md)
- [Documentation index](PLUGINS.md)
- [Python example](../examples/plugins/python-example/)
- [Java example](../examples/plugins/java-example/)

## 1. Choose a plugin format

| | Java plugin | Python plugin |
|---|---|---|
| Package | One JAR file | One directory |
| Entry point | Java class extending Plugin | Python file named by main |
| Descriptor | waterbucket.yml or plugin.yml at JAR root | waterbucket.yml or plugin.yml in plugin directory |
| Runtime | JVM | Embedded GraalPy through GraalVM Polyglot |
| Build step | Compile and package a JAR | None; copy the directory |
| Best fit | Hot paths, larger integrations, maximum JVM control | Small utilities, automation and rapid iteration |

Both formats are discovered by the same PluginManager and share the proxy's event manager, command map, scheduler and core Java APIs.

## 2. Install a plugin

Stop the proxy before changing plugin files, then place the plugin in the proxy's plugins directory.

### Java layout

~~~text
plugins/
└── ExamplePlugin.jar
~~~

The JAR must contain a descriptor at its root and the declared main class.

### Python layout

~~~text
plugins/
└── ExamplePython/
    ├── waterbucket.yml
    ├── main.py
    ├── helper.py
    └── config.yml
~~~

The Python descriptor must be directly inside the plugin directory. The main script path is relative to that directory. Sibling Python modules can be imported.

Python plugin directories are discovered up to two directory levels below the plugins directory (for example, plugins/ExamplePython or plugins/category/ExamplePython). The descriptor must be directly inside the plugin directory. Deeper directory layouts are not discovered by the current scan.

Restart the proxy after installation. The current implementation does not provide a supported plugin hot-reload command.

## 3. Plugin descriptor

Both formats accept **waterbucket.yml** or **plugin.yml**. If both exist, waterbucket.yml is selected.

~~~yaml
name: ExamplePlugin
version: 1.0.0
author: YourName
description: An example WaterbucketPE plugin
main: com.example.waterbucket.ExamplePlugin
depends:
  - AnotherPlugin
~~~

For Python, use a script path for main:

~~~yaml
name: ExamplePython
version: 1.0.0
author: YourName
description: An example Python plugin
main: main.py
depends: []
~~~

### Supported fields

| Field | Required in practice | Meaning |
|---|---:|---|
| name | Yes | Plugin name used by the manager and dependency references |
| main | Yes | Fully qualified Java class name, or a relative .py path for Python |
| version | Recommended | Display/version metadata |
| author | Recommended | Display/author metadata |
| description | Optional | Human-readable summary shown by `wbplugins verbose` |
| depends | Optional | List of plugin names that must be enabled first |

The descriptor model currently contains only these fields. Do not rely on Bukkit/Paper fields such as api-version, softdepend, load, permissions or website; they are not implemented by this descriptor.

The plugin name becomes the data-directory name and the manager key, so it is restricted to letters, digits, spaces, dots, dashes and underscores (maximum 64 characters, no path separators). Descriptors with an invalid name are rejected at load.

Dependencies are hard dependencies. A missing dependency prevents the dependent plugin from enabling. Circular dependency chains (of any length) are detected and rejected without crashing the proxy. Use the exact registered plugin name in depends. There is no documented soft-dependency mechanism.

### Velocity-style alternative: the @PluginDescriptor annotation

A Java plugin jar may declare its descriptor directly on the main class instead of shipping a YAML file. This matches the Velocity workflow: the annotated class IS the main class.

~~~java
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.annotation.PluginDescriptor;

@PluginDescriptor(
        id = "greeter",              // required, unique identity (data-folder name, depends target)
        name = "Greeter",            // optional display name, defaults to the id
        version = "1.0.0",
        authors = {"YourName"},
        description = "Says hello",
        dependencies = {"AnotherPlugin"}   // optional, same hard-dependency semantics as YAML
)
public class GreeterPlugin extends Plugin {
    @Override
    public void onEnable() {
        getProxy().getLogger().info("Hello from " + getName());
    }
}
~~~

Rules, identical in spirit to the YAML parser:

- id is required and follows the plugin-name rules (letters, digits, spaces, dots, dashes, underscores). It becomes the data-directory name and the depends lookup target.
- If the jar also contains waterbucket.yml or plugin.yml, the YAML wins and the annotation is ignored - updating an existing plugin can not change behavior by accident.
- Exactly one annotated class per jar is expected; extra annotated classes are reported and skipped.
- The scan reads metadata only: plugin static initializers never run while the descriptor is being read.

Python plugins are unaffected: they keep using waterbucket.yml.

## 4. Java plugin quick start

Create a class extending:

~~~java
dev.waterbucket.waterbucketpe.plugin.Plugin
~~~

Example:

~~~java
package com.example.waterbucket;

import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;

public final class ExamplePlugin extends Plugin {
    @Override
    public void onStartup() {
        // Runs after the plugin instance is loaded, before enable.
    }

    @Override
    public void onEnable() {
        getLogger().info("ExamplePlugin enabled");

        getProxy().getEventManager().subscribe(
            PlayerChatEvent.class,
            event -> getLogger().info(
                "{}: {}", event.getPlayer().getName(), event.getMessage()
            )
        );

        getProxy().getCommandMap().registerCommand(new Command(
            "example", CommandSettings.builder()
                .setDescription("Example plugin command")
                .setUsageMessage("/example")
                .build()
        ) {
            @Override
            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                sender.sendMessage("Hello from ExamplePlugin");
                return true;
            }
        });
    }

    @Override
    public void onDisable() {
        // Release resources owned by this plugin.
    }
}
~~~

The descriptor main value must be the fully qualified class name, for example **com.example.waterbucket.ExamplePlugin**. The class must extend Plugin and expose a no-argument constructor.

### Compile against WaterbucketPE

The repository builds with Java 17. For a local development setup, place the matching WaterbucketPE release JAR at **libs/Waterbucket.jar** and use it as compile-only:

~~~kotlin
plugins {
    java
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(files("libs/Waterbucket.jar"))
}
~~~

Do not bundle WaterbucketPE itself into your plugin JAR. The proxy supplies its API and runtime dependencies. Build your plugin JAR with the descriptor at the archive root.

## 5. Python plugin quick start

A Python plugin is a folder containing a descriptor and a main script. No external Python installation is required for the bundled runtime.

Descriptor:

~~~yaml
name: ExamplePython
version: 1.0.0
author: YourName
main: main.py
depends: []
~~~

Main script:

~~~python
import java

PlayerChatEvent = java.type(
    "dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent"
)

def on_startup(plugin):
    logger.info("ExamplePython loaded")

def on_enable(plugin):
    plugin.subscribe(PlayerChatEvent, on_chat)

    plugin.register_command(
        "pyhello",
        "Example command from Python",
        "/pyhello",
        None,
        None,
        hello_command
    )

def on_chat(event):
    logger.info(
        "{}: {}",
        event.getPlayer().getName(),
        event.getMessage()
    )

def hello_command(sender, alias, args):
    sender.sendMessage("Hello from Python")
    return True

def on_disable(plugin):
    logger.info("ExamplePython stopping")
~~~

Copy the directory to **plugins/ExamplePython/** and restart the proxy.

### Python module imports

The plugin directory is added to Python's import path. Sibling modules can therefore be imported:

~~~python
# helper.py
def format_greeting(name):
    return "Hello, " + str(name)
~~~

~~~python
# main.py
from helper import format_greeting

def on_enable(plugin):
    logger.info(format_greeting("WaterbucketPE"))
~~~

Keep imported modules inside the plugin directory unless you intentionally manage a shared runtime path.

## 6. Lifecycle and failure behavior

The lifecycle is:

1. **Discovery:** the manager scans JAR files and Python plugin directories and reads descriptors.
2. **Load:** Java main classes are instantiated; Python main scripts are evaluated when the plugin starts up.
3. **Startup:** Java onStartup() or Python on_startup(plugin) runs.
4. **Enable:** Java onEnable() or Python on_enable(plugin) runs after dependencies have been enabled.
5. **Disable:** Java onDisable() or Python on_disable(plugin) runs during proxy shutdown.

Java onEnable() is abstract and must be implemented. Python hooks are looked up by name. The current Python bridge logs a warning when a hook is missing, so define all three hooks even when one is intentionally empty.

A failure while loading or starting one plugin is logged and does not intentionally abort loading every other plugin. A plugin whose enable hook fails stays disabled: the failed state is reverted, onDisable() is not called for it, and the error is logged with its stack trace. Plugins are disabled in reverse enable order so dependents shut down before their dependencies.

Calling `PluginManager.disablePlugin(plugin)` at runtime disables a plugin and every enabled plugin that depends on it (dependents first, cycle-safe). Disabling a plugin also cancels every task the plugin scheduled through the plugin-aware scheduler methods (see section 9) and, for Python plugins, detaches subscriptions and commands.

On disable (or proxy shutdown), WaterbucketPE detaches what a plugin registered where the implementation can do so safely: Python plugins have their event subscriptions removed and their commands unregistered automatically before the interpreter context closes. Java plugins should unsubscribe their event handlers (EventManager.unsubscribe) and unregister their commands (CommandMap.unregisterCommand) in onDisable, because Java handlers registered directly with the event manager are not tracked per plugin.

The current manager is a startup/shutdown lifecycle manager, not a full runtime module manager. There is no supported hot reload, live replacement, or general-purpose unload API.

## 7. Events

Events are registered through the shared EventManager.

### Java subscription

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

getProxy().getEventManager().subscribe(
    ProxyPingEvent.class,
    event -> event.setMotd("Welcome to WaterbucketPE"),
    EventPriority.NORMAL
);
~~~

Priorities are **LOWEST**, **LOW**, **NORMAL**, **HIGH** and **HIGHEST**, in that order. If no priority is specified, NORMAL is used.

### Java annotation listeners (@Subscribe)

The declaration-style alternative to the functional API above, familiar from Velocity: annotate public methods on any listener object and register the whole object at once.

~~~java
import dev.waterbucket.waterbucketpe.event.Subscribe;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerLoginEvent;

public class LoginListener {

    @Subscribe(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLogin(PlayerLoginEvent event) {
        event.getPlayer().sendMessage("Welcome!");
    }
}

// register (in onEnable):
getProxy().getEventManager().registerListener(this, new LoginListener());
~~~

Rules and guarantees:

- Valid listener methods are public, non-static and take exactly one parameter whose type is Event or a subclass of it. Wrong signatures are skipped with a warning naming the method.
- priority uses the same LOWEST..HIGHEST ordering as the functional API; ignoreCancelled = true skips the method when the event is already cancelled (CancellableEvent only).
- A handler that throws is isolated exactly like a functional handler: logged, skipped, pipeline continues.
- Listeners registered with a Plugin owner are detached automatically when the plugin disables (even when onDisable threw). Manual per-instance removal: unregisterListener(listenerObject); owner-wide: unregisterListeners(this).
- Registering the same listener object twice without unregistering in between throws IllegalArgumentException - double delivery is almost never intended.
- Functional and annotation subscriptions can be mixed freely; both see every event.

Python plugins keep the subscribe() facade described below; the annotation API is Java-only (annotations are a Java language feature).

### Python subscription

~~~python
import java

ProxyPingEvent = java.type(
    "dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent"
)

def on_ping(event):
    event.setMotd("Welcome to WaterbucketPE")

def on_enable(plugin):
    plugin.subscribe(ProxyPingEvent, on_ping)
~~~

The Python facade currently exposes subscribe(event_class, handler) and uses the default NORMAL priority. It does not expose a Python priority argument or an unsubscribe handle.

### Event execution rules

- Synchronous events execute handlers on the thread that dispatches the event. Keep handlers short. A slow handler can delay the network path.
- Events marked with the Java @AsyncEvent annotation use the event manager's asynchronous/completable flow. Consult the event class for its exact contract.
- A handler that throws (in either language) is logged with its stack trace and skipped; the remaining handlers and the event pipeline continue. A broken handler never disconnects players or aborts dispatch.
- Cancellation does not stop lower-priority or later handlers from running; all registered handlers always see the event.
- Dispatch matches the exact event class. Subscribing to a parent class (for example PlayerEvent or ProxyPingEvent when ProxyQueryEvent is fired) does not receive subclass events; subscribe to each concrete class you need.
- Cancellable events implement CancellableEvent. Use the event's cancellation API only where that event supports it.
- Do not assume every event is cancellable or asynchronous.
- Do not block a Netty event loop with file, database, network or long CPU work.

### Unsubscribing (Java)

Handlers registered directly with the event manager can be removed by identity:

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

Consumer<ProxyPingEvent> handler = event -> { };
getProxy().getEventManager().subscribe(ProxyPingEvent.class, handler, EventPriority.NORMAL);
// later, e.g. in onDisable():
getProxy().getEventManager().unsubscribe(ProxyPingEvent.class, handler);
~~~

Matching is by the exact consumer instance, so keep the reference you subscribed. Unsubscribing an unknown handler is a no-op. Python plugins do not need this API: their subscriptions are detached automatically on disable.

### Reference recipe: intercept commands and the MOTD

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

@Override
public void onEnable() {
    // Block a command before the proxy or the downstream server sees it
    getProxy().getEventManager().subscribe(PlayerCommandEvent.class, event -> {
        if (event.getCommand().equalsIgnoreCase("/greed")) {
            event.setCancelled(true);              // consumed: neither proxy nor server runs it
            event.getPlayer().sendMessage("§cThat command is disabled.");
        }
        // or rewrite it:
        // event.setCommand("/hub");
    });

    // Customise the server-list MOTD and player counts
    getProxy().getEventManager().subscribe(ProxyPingEvent.class, event -> {
        event.setMotd("§bMy Network");
        event.setMaximumPlayerCount(1000);
    });
}
~~~

### Built-in event classes

The current source tree contains these default event classes:

| Event | Purpose |
|---|---|
| DispatchCommandEvent | Command dispatch |
| FastTransferRequestEvent | Fast-transfer request |
| IncompatibleProtocolEvent | Client protocol incompatibility |
| InitialServerConnectedEvent | Initial downstream connection completed |
| InitialServerDeterminedEvent | Initial downstream selection |
| NetherNetProbeEvent | NetherNet probe flow |
| NetherNetSignalingEvent | NetherNet signaling flow |
| NetworkRegisterEvent | Network interface registration |
| PlayerAuthenticatedEvent | Player authentication |
| PlayerChatEvent | Player chat before forwarding; message can be modified/cancelled |
| PlayerCommandEvent | Player issues any command, before proxy or downstream handling; command can be rewritten or cancelled |
| PlayerDisconnectedEvent | Player disconnect |
| PlayerLoginEvent | Login before initial connect; async and cancellable |
| PlayerPermissionCheckEvent | Permission check |
| PlayerResourcePackApplyEvent | Resource-pack application |
| PlayerResourcePackInfoSendEvent | Resource-pack information sent |
| PostTransferCompleteEvent | Post-transfer completion |
| PreClientDataSetEvent | Client data before it is applied |
| ProxyPingEvent | Bedrock server-list ping; MOTD and player information can be changed |
| ProxyQueryEvent | Query response |
| ProxyStartEvent | Proxy startup completed |
| ResourcePacksRebuildEvent | Resource-pack collection rebuild |
| ServerConnectedEvent | Downstream connection |
| ServerPreConnectEvent | Before opening a downstream connection; async and cancellable |
| ServerTransferEvent | Server transfer |
| ServerTransferFailedEvent | Failed transfer |
| ServerTransferRequestEvent | Transfer request before connect begins |
| TransferCompleteEvent | Transfer dimension/sequence completion |

This table describes event intent, not every event's fields. Read the corresponding class under **src/main/java/dev/waterbucket/waterbucketpe/event/defaults/** for getters, setters, cancellation behavior and annotations. The source class is the authoritative API reference.

## 8. Commands

### Java

Extend Command and register it with the command map:

~~~java
Command command = new Command(
    "hello",
    CommandSettings.builder()
        .setDescription("Sends a greeting")
        .setUsageMessage("/hello")
        .setPermission("example.hello")
        .build()
) {
    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        sender.sendMessage("Hello");
        return true;
    }
};

getProxy().getCommandMap().registerCommand(command);
~~~

The command executor receives the sender, the alias used to invoke the command, and the argument array. Return a boolean to indicate command handling according to the command API.

### Python

~~~python
def hello(sender, alias, args):
    sender.sendMessage("Hello")
    return True

def on_enable(plugin):
    registered = plugin.register_command(
        "hello",
        "Sends a greeting",
        "/hello",
        ["hi"],
        "example.hello",
        hello
    )
    if not registered:
        logger.warning("The command name is already registered")
~~~

Signature:

**register_command(name, description, usage, aliases, permission, handler)**

The handler receives **(sender, alias, args)**. Return a boolean. The method returns false if the command name cannot be registered. Send user feedback explicitly; returning false does not automatically send a usage message.

The Python bridge catches and logs exceptions thrown by a Python command handler.

## 9. Scheduling and ticks

One proxy tick is nominally 50 milliseconds.

### Java scheduler

~~~java
var scheduler = getProxy().getScheduler();

var asyncTask = scheduler.scheduleAsync(() -> {
    // Background work.
});

var delayedTask = scheduler.scheduleDelayed(() -> {
    // Runs after 20 ticks (nominally about one second).
}, 20);

var repeatingTask = scheduler.scheduleRepeating(() -> {
    // Runs every 100 ticks.
}, 100);

// Java scheduling methods return TaskHandler instances.
// Retain the handle and call cancel() when the task should stop.
repeatingTask.cancel();
~~~

The scheduler supports asynchronous tasks, synchronous tasks, delayed tasks and repeating tasks. The overloads without an async flag use synchronous execution for delayed/repeating tasks; scheduleAsync is asynchronous. Use the overloads with an explicit async flag when needed.

### Plugin-owned tasks (Java)

Scheduling with a plugin reference registers the task to the plugin. Every task scheduled this way is cancelled automatically when the plugin disables, so a repeating task can not outlive its owner:

~~~java
var scheduler = getProxy().getScheduler();

// Runs every 40 ticks after a 20 tick delay; auto-cancelled on plugin disable
scheduler.scheduleRepeating(this, () -> refreshCache(), 20, 40);

// Delayed one-shot on behalf of this plugin
scheduler.scheduleDelayed(this, () -> saveAll(), 100);

// Or cancel everything owned by this plugin manually, e.g. in onDisable():
scheduler.cancelTasks(this);

// Cancel a single task by its id:
scheduler.cancelTask(taskHandler.getTaskId());
~~~

### Python scheduler facade

~~~python
plugin.schedule_async(do_background_work)
plugin.schedule_delayed(run_once, 20)
plugin.schedule_repeating(check_status, 100)
~~~

Python helper methods schedule their work asynchronously. Their current signatures return no TaskHandler, so Python plugins cannot cancel these tasks through the facade. Do not create permanent repeating tasks unless they are intended to live until proxy shutdown. A future API may add plugin-owned task handles; do not assume that capability exists today.

Do not use synchronous tasks for blocking I/O or long-running calculations.

### Time-based scheduling (Velocity-style TaskBuilder)

For schedules expressed in real time instead of ticks, use the fluent builder. These tasks run on a dedicated time-scheduler pool, so their cadence is independent of the tick loop:

~~~java
var scheduler = getProxy().getScheduler();

// One-shot after 10 seconds
scheduler.buildTask(this, () -> announceEvent())
        .delay(10, TimeUnit.SECONDS)
        .schedule();

// Every 500 milliseconds, first run after 2 seconds, auto-cancelled on plugin disable
ScheduledTask task = scheduler.buildTask(this, () -> pingDownstreams())
        .delay(2, TimeUnit.SECONDS)
        .repeat(500, TimeUnit.MILLISECONDS)
        .schedule();

// Stop it any time:
task.cancel();
~~~

Semantics:

- repeat = 0 (the default) makes the task one-shot after the delay; repeat > 0 uses fixed-delay semantics - the next repetition starts a full period after the previous one finished, so a slow task backs up instead of overlapping itself.
- Negative delay/repeat and null time units throw IllegalArgumentException at the builder call; schedule() then registers the task.
- Tasks built with a Plugin owner join the same auto-cancel contract as tick tasks: cancelling the plugin cancels them, and scheduler.cancelTasks(this) works across both APIs.
- Exceptions thrown by the task are logged with the task id and owner name; the schedule survives. A repeating task never dies silently.
- The tasks always run on the time-scheduler pool, never on Netty event loops or the tick thread - use thread-safe proxy APIs inside them.
- The ProxyServer-side aliases: proxy.fire(event) is callEvent(event), and proxy.getAllPlayers() is getPlayers().values().

## 10. Working with the proxy

`getProxy()` returns the ProxyServer singleton (`ProxyServer.getInstance()` works too). This is the entry point to every manager and to the player/server registry.

### Players

~~~java
ProxiedPlayer player = getProxy().getPlayer("Steve");          // by name (null if offline)
ProxiedPlayer byId  = getProxy().getPlayer(uuid);              // by UUID
ProxiedPlayer exact = getProxy().getPlayerExact("Steve");      // case-insensitive exact match
java.util.List<ProxiedPlayer> matches = getProxy().matchPlayer("ste"); // partial name match
java.util.Map<java.util.UUID, ProxiedPlayer> online = getProxy().getPlayers();
~~~

ProxiedPlayer exposes, among others: `connect(ServerInfo)`, `sendMessage(String|TextContainer)`, `sendTranslation(...)`, `chat(String)`, `sendPopup`, `sendTip`, `sendTitle`, `sendToastMessage`, `disconnect(reason)`, `sendToFallback(...)`, `kick`, permission methods (`addPermission`, `hasPermission`, `removePermission`), device/protocol info (`getDevicePlatform`, `getDeviceModel`, `getDeviceId`, `getProtocol`), identity (`getName`, `getUniqueId`, `getXuid`), network state (`getAddress`, `getPing`, `isConnected`, `getServerInfo`, `getDownstreamConnection`) and resource-pack controls (`acceptResourcePacks`). Permission checks fire `PlayerPermissionCheckEvent`, so plugins can override the result.

### Connection requests (Velocity-style)

Instead of calling connect() and inferring the outcome from events, a connection request returns the actual result:

~~~java
import dev.waterbucket.waterbucketpe.player.ConnectionRequest;
import dev.waterbucket.waterbucketpe.player.ConnectionResult;
import dev.waterbucket.waterbucketpe.player.ConnectionResultStatus;

player.createConnectionRequest(getProxy().getServerInfo("minigames"))
        .connectWithIndication()
        .thenAccept((ConnectionResult result) -> {
            switch (result.getStatus()) {
                case SUCCESS -> { /* the player is now connecting to / on the target */ }
                case ALREADY_CONNECTED -> { }
                case CONNECTION_IN_PROGRESS -> { }
                case CONNECTION_CANCELLED -> { /* another plugin cancelled a transfer event */ }
                case CONNECTION_FAILED -> {
                    String detail = result.getMessage();  // dial error, timeout notice, ...
                }
            }
        });

// Or without the result handling:
player.createConnectionRequest(getProxy().getServerInfo("lobby1")).fireAndForget();
~~~

Contract:

- The future always completes **normally** with a ConnectionResult - failures are statuses, never exceptions - at the latest after ConnectionRequest.REQUEST_TIMEOUT_SECONDS (90s).
- SUCCESS means the proxy accepted the downstream connection (handshake complete). Later downstream failures still surface through the transfer events and the normal fallback handling; the request result only covers the requested dial.
- Exactly one status wins: a second request for the same target while one is dialing settles immediately with CONNECTION_IN_PROGRESS, so requests are effectively serialized per player.
- The result never blocks the calling thread unless you call .join() yourself; the request machinery touches nothing on the packet hot path.
- ALREADY_CONNECTED and CONNECTION_IN_PROGRESS reflect the same conditions the proxy's own connect() checks, so the status matches what the proxy actually did.

### Servers

~~~java
ServerInfo lobby = getProxy().getServerInfo("lobby1");         // by name (null if unknown)
ServerInfo byAddr  = getProxy().getServerInfo(address, port);  // by address
java.util.Collection<ServerInfo> servers = getProxy().getServers();
getProxy().registerServerInfo(newServerInfo);                  // register at runtime
getProxy().removeServerInfo("lobby1");                         // unregister at runtime
~~~

### Maximum player count

The effective limit comes from `listener.max_players` in config.yml and can be changed at runtime:

~~~java
int max = getProxy().getMaxPlayers();          // override if set, otherwise the config value
getProxy().setMaxPlayers(100);                 // runtime change + saved to config.yml
getProxy().setMaxPlayers(100, false);          // runtime-only, does not touch config.yml
boolean full = getProxy().isFull(player);      // true when the limit is reached and the player has no bypass
~~~

Notes:

- `setMaxPlayers(0)` disables the limit. Negative values throw `IllegalArgumentException`.
- The effective value drives the server-list ping and query responses, the `/wbstatus` and panel displays, and login enforcement: when the proxy is full, new players are disconnected with the `waterbucket.server.full` message unless they hold the `waterbucket.bypass.maxplayers` permission.
- The same functionality is available from the console/in-game through `/wbmaxplayers [count]`.
- Handlers in `PlayerLoginEvent` with a higher priority run after the full check and can still admit a player by connecting them in the handler.

### Broadcasting

~~~java
getProxy().broadcastMessage("§aMaintenance in 5 minutes!");
getProxy().broadcastMessage(new TranslationContainer("waterbucket.server.shutdown"));
~~~

Both overloads deliver the message to every online player and the console.

### Managers

| Accessor | Purpose |
|---|---|
| getEventManager() | Subscribe/unsubscribe/call events (section 7) |
| getCommandMap() | Register/unregister commands (section 8) |
| getScheduler() | Task scheduling (section 9) |
| getPluginManager() | Look up plugins: `getPluginByName`, `getPlugins`, `getPluginMap`, `enablePlugin`, `disablePlugin` |
| getConfiguration() | Live ProxyConfig for config.yml values |
| getLogger() | Proxy logger |
| getPlayerManager() | Player registry internals |
| getPackManager() | Resource pack management |

### Join, fallback and forced-host handlers

The routing decisions can be replaced with custom implementations:

~~~java
import dev.waterbucket.waterbucketpe.network.connection.handler.IJoinHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.IReconnectHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;

// Which server a player lands on when they join
getProxy().setJoinHandler(player ->
    getProxy().getServerInfo("lobby1"));

// Where a player goes when a downstream server kicks them (fallback)
getProxy().setReconnectHandler((player, oldServer, reason, kickMessage) -> {
    if (reason == ReconnectReason.SERVER_KICK && kickMessage.contains("banned")) {
        return null;                     // null disconnects the player from the proxy
    }
    return getProxy().getServerInfo("lobby1");
});
~~~

`ReconnectReason` values are `UNKNOWN`, `TIMEOUT`, `EXCEPTION`, `SERVER_KICK` and `TRANSFER_FAILED`. Default implementations ship with the proxy (`DefaultJoinHandler`, `DefaultReconnectHandler`, `RoundRobinReconnectHandler`) and the configured ones are read from config.yml. Never set a handler to null; use a no-op implementation instead. Both handlers run on the connection path: keep them fast and do slow work in scheduled background tasks (section 9).

## 11. Configuration and plugin resources

Each plugin receives a data directory based on its lower-case name:

~~~text
plugins/
├── ExamplePlugin.jar
└── exampleplugin/
    └── config.yml
~~~

Java:

~~~java
var config = getConfig();       // Loads config.yml on first access.
String mode = (String) config.get("settings.mode", "safe");
config.set("settings.mode", "fast");
config.save();
~~~

Python:

~~~python
config = plugin.getConfig()
mode = config.get("settings.mode", "safe")
config.set("settings.mode", "fast")
config.save()
~~~

Configuration supports dotted paths for nested keys, get(key), get(key, default), set(key, value), remove(key), exists(key), getKeys() and save(). Values are YAML-backed through YamlConfig.

Place a default **config.yml** at the root of the Python plugin directory or at the root of the Java JAR. Calling getConfig()/loadConfig() copies the bundled config to the plugin data folder when it does not already exist, then loads it. Existing user configuration is not overwritten by the default save operation. Call `reloadConfig()` (Java) to discard the loaded state and re-read config.yml from disk.

Resource access:

- Java: getResourceFile(path) returns an InputStream with the full content of the resource inside the plugin JAR; missing entries return null (no exception). Close it with try-with-resources.
- Python: getResourceFile(path) reads a file relative to the Python plugin folder; paths that escape the folder (for example with ../) are rejected and return null.
- Both: saveResource(path) copies a bundled resource into the plugin data directory; saveResource(source, outputName, replace) controls the destination name and overwrite behavior.

Example:

~~~java
saveResource("messages.yml", false);
~~~

~~~python
plugin.saveResource("messages.yml")
~~~

## 12. Python runtime, Java interop and compatibility

Python plugins run in the embedded GraalVM Polyglot Python runtime included in the proxy distribution. The runtime is initialized when a Python plugin context is created; Java-only installations do not need to start a Python context.

The bridge exposes these globals to each Python script:

| Global | Meaning |
|---|---|
| plugin | Current PythonPlugin facade |
| proxy | ProxyServer instance |
| logger | Plugin Log4j logger |

Java interop is enabled. Import java and use **java.type("fully.qualified.ClassName")** to access Java classes. The context is created with allowAllAccess(true), which is powerful and intentionally not a sandbox.

Python facade methods implemented directly by PythonPlugin:

| Method | Arguments | Behavior |
|---|---|---|
| subscribe | event_class, handler | Subscribe at NORMAL priority |
| register_command / registerCommand | name, description, usage, aliases, permission, handler | Register a proxy command |
| schedule_async / scheduleAsync | task | Schedule asynchronous work |
| schedule_delayed / scheduleDelayed | task, delay_ticks | Schedule delayed asynchronous work |
| schedule_repeating / scheduleRepeating | task, period_ticks | Schedule repeating asynchronous work |
| getProxy | none | Return ProxyServer |
| getResourceFile | filename | Open a file relative to the Python plugin folder |

The inherited Plugin API also provides methods/properties such as getName(), getDescription(), getLogger(), getPluginFile(), getDataFolder(), getConfig(), loadConfig(), saveResource(), isEnabled() and getProxy().

The Python facade is additive; it does not replace the Java API. Python can call other public Java methods through interop, but that does not mean every Java method has a Python-friendly wrapper.

### Compatibility notes

- Java plugins must extend the WaterbucketPE Plugin class and compile against the API available to the target proxy version.
- The source package is **dev.waterbucket.waterbucketpe**. A plugin compiled against a different package namespace may need source changes and recompilation.
- WaterdogPE compatibility is not a blanket guarantee for every third-party binary. Validate each plugin against the current release and its dependencies.
- The descriptor supports **depends**, not the full metadata schema of Bukkit/Paper.
- Python plugin API is evolving; use the methods documented above rather than assuming a Bukkit-style API.

## 13. Security and performance guidance

### Security

Python contexts are created with **allowAllAccess(true)**. A Python plugin can access Java classes, files, processes, networking and other capabilities available to the proxy process. Treat Python and Java plugins as trusted code. Install plugins only from sources you trust.

Do not expose secrets in logs or commit API tokens/passwords to plugin folders. Validate user input in commands and event handlers. A plugin is not isolated from the proxy merely because it is written in Python.

### Performance

- Prefer Java for extremely frequent events, packet-adjacent work and latency-sensitive logic.
- Keep synchronous event handlers short; they run inline on the dispatching thread.
- Offload blocking file/network/database work with the scheduler.
- Avoid expensive Java/Python conversions inside high-frequency loops.
- Cache Java class references (java.type results) rather than resolving them repeatedly.
- Avoid unbounded repeating tasks and unbounded in-memory collections.
- Remember that adding a Python runtime/context has memory and startup costs. The interpreter is not a zero-cost feature when Python plugins are installed.
- The proxy's internal executors (scheduler and event dispatch) use fixed-resident bounded pools: no extra thread spawns per burst, so RAM and CPU stay flat instead of bouncing. Follow the same pattern in your plugin; do not create your own unbounded thread production.

### Lifecycle limitations to account for

Python scheduling helpers do not return cancellation handles, so Python plugins cannot cancel their own tasks through the facade. There is no runtime hot reload, unload, or per-plugin isolation. Do not design plugins around repeated enable/disable cycles; the supported model is enable once at startup, then disable at shutdown (or when another plugin calls setEnabled(false)). When a plugin is disabled, its Python event subscriptions and commands are detached automatically; keep other external resources (files, connections) release-aware in onDisable.

## 14. Troubleshooting

| Symptom | Likely cause | What to check |
|---|---|---|
| Plugin is not discovered | Wrong folder depth or file extension | JAR in plugins tree; Python descriptor directly inside one plugin folder |
| Missing descriptor warning | Descriptor absent or invalid YAML | Use waterbucket.yml or plugin.yml at the required root |
| Main class cannot load | Wrong Java package/class name or missing class | Check main and ensure the class is inside the JAR |
| Python main file does not exist | main path is wrong | Path is relative to the Python plugin folder and ends in .py |
| Python script fails to load | Syntax/import/runtime error | Read the full Python traceback in proxy logs |
| ModuleNotFoundError | Module not on import path | Keep helper modules beside main.py or install dependencies into a supported runtime path |
| Hook warning | Hook missing or not callable | Define on_startup, on_enable and on_disable |
| Event handler does not run | Wrong class name or plugin did not enable | Use the fully qualified class with java.type and inspect startup logs |
| Command registration returns false | Another command already owns the name | Choose a unique command name |
| Config value is missing | Wrong dotted key or YAML type | Inspect config.yml and use a default value |
| Python package fails to install | Package may require unsupported native extensions | Check GraalPy compatibility; not every CPython wheel works |

## 15. Testing and development checklist

Before publishing a plugin:

- Test on the exact WaterbucketPE version you target.
- Test with the plugin's declared dependencies installed and missing.
- Verify startup, enable and disable hooks.
- Test event cancellation only on cancellable events.
- Test commands from both console and player contexts where applicable.
- Test scheduler behavior under load and ensure repeating tasks are bounded.
- Test configuration creation, edits and restart persistence.
- Test with malformed input and plugin exceptions.
- Do not block synchronous event handlers.
- Document permissions and external services your own plugin requires.

## 16. API source references

Use the source as the final authority:

- Java base class: **src/main/java/dev/waterbucket/waterbucketpe/plugin/Plugin.java**
- Descriptor: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginYAML.java**
- Discovery/lifecycle: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginManager.java**
- Python bridge: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPlugin.java**
- Python loader: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPluginLoader.java**
- Event manager: **src/main/java/dev/waterbucket/waterbucketpe/event/EventManager.java**
- Scheduler: **src/main/java/dev/waterbucket/waterbucketpe/scheduler/WaterbucketScheduler.java**
