# WaterbucketPE Plugin API — Java & Python

WaterbucketPE ships one plugin API with **two language front-ends**. Java plugins are jar
files compiled against the proxy; Python plugins are folders evaluated by an embedded Python
3.14 interpreter (GraalPy). Both share the same lifecycle, the same event bus, the same
command registry and the same configuration helpers — and both can run side by side.

> Compatibility guarantee: the Java plugin API is frozen. Existing WaterdogPE/WaterbucketPE
> jar plugins keep working without recompilation; the Python front-end is purely additive.

---

## 1. Quick start

### Java plugin (jar)

```
plugins/
└── MyPlugin.jar      <- jar with waterbucket.yml inside and a class extending Plugin
```

`waterbucket.yml` (inside the jar root; `plugin.yml` also accepted):

```yaml
name: MyPlugin
version: 1.0.0
author: You
main: com.example.myplugin.MyPlugin
depends: []          # optional: names of plugins that must load first
```

```java
public class MyPlugin extends Plugin {

    @Override
    public void onStartup() {
        // called once when the plugin is loaded (before enable)
    }

    @Override
    public void onEnable() {
        getLogger().info("Hello from Java!");
        getProxy().getEventManager().subscribe(PlayerChatEvent.class, event ->
                getLogger().info("{} said {}", event.getPlayer().getName(), event.getMessage()));
    }

    @Override
    public void onDisable() {
        // release resources here
    }
}
```

Build against the proxy (Gradle example):

```kotlin
repositories { maven("https://repo.waterdog.dev/main"); mavenCentral() }
dependencies { compileOnly("dev.waterbucket.waterbucketpe:waterbucketpe:1.1.0") }
```

### Python plugin (folder)

```
plugins/
└── HelloPy/
    ├── waterbucket.yml
    ├── main.py
    └── helper.py        <- any sibling .py file is importable
```

`waterbucket.yml`:

```yaml
name: HelloPy
version: 1.0.0
author: You
main: main.py
depends: []
```

`main.py`:

```python
import java

def on_startup(plugin):
    pass

def on_enable(plugin):
    PlayerChatEvent = java.type("dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent")
    plugin.subscribe(PlayerChatEvent, on_chat)

def on_chat(event):
    logger.info("{} said {}", event.getPlayer().getName(), event.getMessage())

def on_disable(plugin):
    pass
```

Copy the folder into `plugins/` and start the proxy — no compilation step.

---

## 2. Lifecycle

| Phase | Java | Python | Notes |
|---|---|---|---|
| Load | `onStartup()` | `on_startup(plugin)` | class/script loaded; register custom event types here |
| Enable | `onEnable()` | `on_enable(plugin)` | called by `PluginManager` in dependency order |
| Disable | `onDisable()` | `on_disable(plugin)` | reverse enable order on shutdown |

- `depends: [OtherPlugin]` makes this plugin load (and enable) after `OtherPlugin`.
- A thrown exception in `onStartup`/`onEnable` disables the plugin; other plugins continue.
- On shutdown, plugins are disabled in reverse enable order so dependents stop first.

## 3. Events

All events live in `dev.waterbucket.waterbucketpe.event.defaults` and extend `Event`.

- **Sync events** run inline on the network thread that triggered them — exactly like Java
  handlers. A handler may cancel a `CancellableEvent` before the pipeline continues.
- **Async events** (annotated `@AsyncEvent`) run on the event executor; the pipeline waits
  for every handler, including Python ones.

Java:

```java
getProxy().getEventManager().subscribe(
        ProxyPingEvent.class,
        event -> event.setMotd("WaterbucketPE!"),
        EventPriority.NORMAL);     // LOW runs before NORMAL before HIGH
```

Python:

```python
ProxyPingEvent = java.type("dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent")

def on_ping(event):
    event.setMotd("WaterbucketPE!")

plugin.subscribe(ProxyPingEvent, on_ping)
```

Rules that apply to both languages:

1. Never block inside a sync handler (no `Thread.sleep`, no long IO).
2. Handlers must not assume they are on a specific thread; use
   `plugin.schedule_async(...)` / the Java scheduler for slow work.
3. One broken handler never breaks the event pipeline or other handlers.

## 4. Commands

Java — extend `Command`:

```java
public class PingCommand extends Command {
    public PingCommand() {
        super("ping", CommandSettings.builder()
                .setDescription("Check the proxy latency")
                .setUsageMessage("/ping")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        sender.sendMessage("pong");
        return true;
    }
}
// registration:
getProxy().getCommandMap().registerCommand(new PingCommand());
```

Python — pass a callable `handler(sender, alias, args) -> bool`:

```python
def ping_command(sender, alias, args):
    sender.sendMessage("pong")
    return True

plugin.register_command("ping", "Check the proxy latency", "/ping", None, None, ping_command)
```

Returning `False` (or `None`) does not send feedback automatically — send messages yourself.
Commands registered while the player list is being rebuilt appear in the client command UI.

## 5. Scheduling

| Task | Java | Python |
|---|---|---|
| Run once, async | `getProxy().getScheduler().scheduleAsync(task)` | `plugin.schedule_async(task)` |
| Delayed (ticks) | `scheduleDelayed(task, delay)` | `plugin.schedule_delayed(task, ticks)` |
| Repeating (ticks) | `scheduleRepeating(task, period)` | `plugin.schedule_repeating(task, ticks)` |

One tick = 50 ms. Sync (non-async) tasks run on the tick thread — keep them short. Cancel a
task with the returned `TaskHandler`'s `cancel()`.

## 6. Configuration and data folder

Both front-ends get a per-plugin data folder `plugins/<lowercase-name>/`:

```java
getConfig().save();                    // Java: YamlConfig backed config.yml
```

```python
import java
YamlConfig = java.type("dev.waterbucket.waterbucketpe.utils.config.YamlConfig")
config = YamlConfig(java.io.File(plugin.getDataFolder(), "config.yml"))
```

`plugin.getResourceFile(name)` reads a bundled resource (inside the jar for Java plugins,
next to `main.py` for Python plugins); `plugin.saveResource(name)` copies it to the data
folder. `loadConfig()` in Java does both for `config.yml`.

## 7. The Python runtime in depth

WaterbucketPE embeds [GraalPy](https://github.com/oracle/graalpython) (Python 3.14). A few
facts worth knowing:

- **Lazy**: the interpreter only starts when the first Python plugin loads. Servers without
  Python plugins pay no memory or startup cost.
- **Thread-safe**: GraalPy contexts serialize execution internally, so event handlers may run
  from any Netty event loop concurrently without extra locking. The interpreter is still one
  CPU core — keep handlers short and offload heavy work to the scheduler.
- **Full Java interop**: `import java` exposes the whole JVM:
  ```python
  import java
  ProxyServer = java.type("dev.waterbucket.waterbucketpe.ProxyServer")
  proxy = ProxyServer.getInstance()
  ```
  Java collections, strings and numbers map to native Python types transparently; call
  `.asInt()`, `.asString()` style conversions happen automatically on assignment.
- **Globals**: every script runs with `plugin` (this facade), `proxy` (ProxyServer) and
  `logger` (the plugin logger) pre-bound.
- **Dependencies**: drop pure-Python packages into the plugin folder (they are importable)
  or install them into the proxy's Python environment; C-extension wheels are not supported.
- **Isolation**: each plugin gets its own context; `on_disable` closes it. A crashed Python
  plugin never takes the proxy down.

## 8. Python facade reference

| Member | Signature | Purpose |
|---|---|---|
| `subscribe` | `subscribe(event_class, handler)` | register an event handler |
| `register_command` | `register_command(name, description, usage, aliases, permission, handler)` | register a command; `handler(sender, alias, args) -> bool` |
| `schedule_async` | `schedule_async(task)` | run `task` on the async scheduler |
| `schedule_delayed` | `schedule_delayed(task, delay_ticks)` | run `task` after N ticks |
| `schedule_repeating` | `schedule_repeating(task, period_ticks)` | run `task` every N ticks |
| `getProxy()` | — | the `ProxyServer` instance |
| `getLogger()` | — | Log4j2 logger of the plugin (also bound as global `logger`) |
| `getDataFolder()` | — | `java.io.File` data folder |
| `getConfig()` / `loadConfig()` | — | `config.yml` handling |
| `getResourceFile(name)` / `saveResource(name)` | — | bundled resources |
| `getName()` / `getVersion()` | — | descriptor values |

## 9. Event reference (highlights)

| Event | Type | Fired when |
|---|---|---|
| `PlayerLoginEvent` | async | a client completes login; cancel to deny |
| `PlayerChatEvent` | sync | chat message sent |
| `ProxyPingEvent` | sync | server list ping; edit MOTD/protocol |
| `ProxyQueryEvent` | sync | GameSpy4 query; edit map/whitelist/players |
| `ServerPreConnectEvent` | async | before connecting to a downstream; retarget or cancel |
| `ServerTransferRequestEvent` | sync | before a `connect()` starts |
| `TransferCompleteEvent` | sync | transfer dimension sequence finished |
| `ServerTransferFailedEvent` | sync | transfer failed; fallback already attempted |
| `PlayerDisconnectedEvent` | sync | player left the proxy |
| `ProxyStartEvent` | sync | proxy finished booting |

See `src/main/java/dev/waterbucket/waterbucketpe/event/defaults/` for the full list.

## 10. Worked examples

- `examples/plugins/python-example/` — copy to `plugins/` and start the proxy.
- `examples/plugins/java-example/` — drop the sources into your own Gradle/Maven project and
  build against the proxy dependency.

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Python plugin ... declares main 'x.py' which does not exist` | descriptor path wrong | `main` is relative to the plugin folder |
| `Failed to load Python plugin ...!` | script syntax error | check the stack trace in the log |
| `ModuleNotFoundError` in script | sibling import | keep helper `.py` files next to `main.py` |
| Plugin never enables | exception in `on_enable` | logged with full Python traceback |
| Handler never fires | wrong event class name | use the exact `java.type("...defaults.X")` path |
