# Plugin documentation

WaterbucketPE supports three plugin languages: **Java (JAR)**, **Python (folder-based, embedded GraalPy)** and **PHP (folder-based, embedded Quercus)**.

Choose your language:

- [English — complete plugin guide](PLUGINS.en.md)
- [فارسی — راهنمای کامل پلاگین‌نویسی](PLUGINS.fa.md)

Both guides describe the implementation in the current source tree. They intentionally distinguish implemented APIs from features that are not currently provided (such as plugin hot reload and Python task handles).

The Java plugin API covers the full upstream surface (functional events, commands, tick scheduling, player/server management, runtime max players) plus the declarative additions: `@PluginDescriptor` jar declaration, `@Subscribe` annotation listeners, the fluent `TaskBuilder` time scheduler, and `ConnectionRequest` with a real result future. See sections 3, 7, 9 and 10 of the guides.

Script plugins (Python and PHP) run on embedded interpreters shipped inside the proxy - no system Python or PHP installation is required - and expose the same lifecycle hooks, event subscriptions, commands and scheduling helpers through their facades. See sections 5 and 12 of the guides.

## Runnable examples

- [Python example plugin](../examples/plugins/python-example/)
- [PHP example plugin](../examples/plugins/php-example/)
- [Java example project](../examples/plugins/java-example/)

## Source of truth

The implementation lives under:

- Java plugin lifecycle and descriptor: `src/main/java/dev/waterbucket/waterbucketpe/plugin/`
- Embedded Python bridge: `src/main/java/dev/waterbucket/waterbucketpe/plugin/python/`
- Embedded PHP bridge: `src/main/java/dev/waterbucket/waterbucketpe/plugin/php/`
- Event API: `src/main/java/dev/waterbucket/waterbucketpe/event/`
- Scheduler API: `src/main/java/dev/waterbucket/waterbucketpe/scheduler/`

If documentation and source disagree, the source code for the checked-out revision is authoritative.
