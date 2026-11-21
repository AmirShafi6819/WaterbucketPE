# Plugin documentation

WaterbucketPE supports two plugin languages: **Java (JAR)** and **Python (folder-based, embedded GraalPy)**.

Choose your language:

- [English — complete plugin guide](PLUGINS.en.md)
- [فارسی — راهنمای کامل پلاگین‌نویسی](PLUGINS.fa.md)

Both guides describe the implementation in the current source tree. They intentionally distinguish implemented APIs from features that are not currently provided (such as plugin hot reload and Python task handles).

## Runnable examples

- [Python example plugin](../examples/plugins/python-example/)
- [Java example project](../examples/plugins/java-example/)

## Source of truth

The implementation lives under:

- Java plugin lifecycle and descriptor: `src/main/java/dev/waterbucket/waterbucketpe/plugin/`
- Embedded Python bridge: `src/main/java/dev/waterbucket/waterbucketpe/plugin/python/`
- Event API: `src/main/java/dev/waterbucket/waterbucketpe/event/`
- Scheduler API: `src/main/java/dev/waterbucket/waterbucketpe/scheduler/`

If documentation and source disagree, the source code for the checked-out revision is authoritative.
