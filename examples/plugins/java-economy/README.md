# EconomyAPI example (compatibility surface)

The classic economy plugin of the original server software, rebuilt on the proxy through the
compatibility plugin surface only: one `Config` file for the balances, the compatibility
`PlayerJoinEvent` for automatic account creation, and the `/money`, `/pay` and `/eco`
commands. Compiles against the proxy jar (`libs/Waterbucket.jar`), exactly like the Java
example next to it - see `../java-example` for how to build.
