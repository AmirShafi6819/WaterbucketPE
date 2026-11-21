# Java plugin example

This is a minimal Java 17 project template for a WaterbucketPE Java plugin.

## Build

1. Copy the WaterbucketPE release JAR to **libs/Waterbucket.jar**.
2. Run **gradle build** with an installed Gradle 8+ distribution.
3. Copy the resulting plugin JAR from **build/libs/** into the proxy's **plugins/** directory.
4. Restart WaterbucketPE.

The WaterbucketPE dependency is compile-only. Do not shade or bundle the proxy JAR into your plugin.

The example subscribes to PlayerChatEvent and registers **/javahello**.
