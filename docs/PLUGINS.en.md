# WaterbucketPE Plugin Development (Java & Python)

This guide documents the plugin system implemented in WaterbucketPE 1.1.0. It covers installation, descriptors, lifecycle, events, commands, scheduling, configuration, resources, Java interop, compatibility, security, performance and troubleshooting.

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

Python plugins are directory-based and are discovered one directory below the plugins directory. Do not put a Python plugin inside another nested directory.

Restart the proxy after installation. The current implementation does not provide a supported plugin hot-reload command.

## 3. Plugin descriptor

Both formats accept **waterbucket.yml** or **plugin.yml**. If both exist, waterbucket.yml is selected.

~~~yaml
name: ExamplePlugin
version: 1.0.0
author: YourName
main: com.example.waterbucket.ExamplePlugin
depends:
  - AnotherPlugin
~~~

For Python, use a script path for main:

~~~yaml
name: ExamplePython
version: 1.0.0
author: YourName
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
| depends | Optional | List of plugin names that must be enabled first |

The descriptor model currently contains only these fields. Do not rely on Bukkit/Paper fields such as api-version, softdepend, load, permissions or website; they are not implemented by this descriptor.

Dependencies are hard dependencies. A missing dependency prevents the dependent plugin from enabling. Circular dependency chains are rejected. Use the exact registered plugin name in depends. There is no documented soft-dependency mechanism.

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

A failure while loading or starting one plugin is logged and does not intentionally abort loading every other plugin. A plugin whose enable hook fails is treated as failed. Plugins are disabled in reverse enable order so dependents shut down before their dependencies.

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
- Cancellable events implement CancellableEvent. Use the event's cancellation API only where that event supports it.
- Do not assume every event is cancellable or asynchronous.
- Do not block a Netty event loop with file, database, network or long CPU work.

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

### Python scheduler facade

~~~python
plugin.schedule_async(do_background_work)
plugin.schedule_delayed(run_once, 20)
plugin.schedule_repeating(check_status, 100)
~~~

Python helper methods schedule their work asynchronously. Their current signatures return no TaskHandler, so Python plugins cannot cancel these tasks through the facade. Do not create permanent repeating tasks unless they are intended to live until proxy shutdown. A future API may add plugin-owned task handles; do not assume that capability exists today.

Do not use synchronous tasks for blocking I/O or long-running calculations.

## 10. Configuration and plugin resources

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

Place a default **config.yml** beside the Python main script or at the root of the Java JAR. Calling getConfig()/loadConfig() copies the bundled config to the plugin data folder when it does not already exist, then loads it. Existing user configuration is not overwritten by the default save operation.

Resource access:

- Java: getResourceFile(path) returns an InputStream from the plugin JAR. Close it with try-with-resources.
- Python: getResourceFile(path) reads a file relative to the plugin directory.
- Both: saveResource(path) copies a bundled resource into the plugin data directory; saveResource(source, outputName, replace) controls the destination name and overwrite behavior.

Example:

~~~java
saveResource("messages.yml", false);
~~~

~~~python
plugin.saveResource("messages.yml")
~~~

## 11. Python runtime, Java interop and compatibility

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

## 12. Security and performance guidance

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

### Lifecycle limitations to account for

The current event API does not expose a public unsubscribe handle in the plugin facade, and Python scheduling helpers do not return cancellation handles. Do not design plugins around runtime reload/unload or repeated enable/disable cycles. Keep registrations bounded and tasks finite where possible. These are current API limitations, not promises that registrations are automatically cleaned up on plugin disable.

## 13. Troubleshooting

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

## 14. Testing and development checklist

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

## 15. API source references

Use the source as the final authority:

- Java base class: **src/main/java/dev/waterbucket/waterbucketpe/plugin/Plugin.java**
- Descriptor: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginYAML.java**
- Discovery/lifecycle: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginManager.java**
- Python bridge: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPlugin.java**
- Python loader: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPluginLoader.java**
- Event manager: **src/main/java/dev/waterbucket/waterbucketpe/event/EventManager.java**
- Scheduler: **src/main/java/dev/waterbucket/waterbucketpe/scheduler/WaterbucketScheduler.java**
