# WaterbucketForwarding

The backend-side half of the WaterbucketPE forwarding, for PocketMine-MP servers.

The proxy embeds every player's real address and Xbox identity into the client data object
of the login packet. PocketMine-MP parses that object strictly, so the forwarded keys would
abort the login with `JSON property "Waterdog_IP" does not exist in object of type
...ClientData` - this plugin makes the backend read them instead:

- **Real IP** - the session carries the player's real address from login on, so server logs,
  bans and plugins see the player instead of the proxy's dial address (127.0.0.1).
- **Real identity** - the forwarded XUID is applied to the session, so account plugins can
  bind the true owner.
- **Only Proxy Join, protocol-level** - with `require-proxy: true`, joins from any address
  the proxy does not dial from are refused with the configured kick message. No firewall
  rules needed: the backend itself rejects players that bypassed the proxy.

## Install

1. Copy `WaterbucketForwarding.phar` (or build it from this folder with `bash ../build-phar.sh`)
   into the backend's `plugins/` folder.
2. In `server.properties` set `xbox-auth=off` - the proxy re-signs the login chain, so the
   backend cannot verify Mojang signatures behind it; the proxy does that check
   (`online_mode` in the proxy config).
3. In `pocketmine.yml` set `player.verify-xuid: false` - the XUID arrives through the
   forwarded login data.
4. Restart the backend. Its log shows one line: `WaterbucketPE forwarding active - ...`.

If the proxy and this plugin run on the same host, the default config works as-is. When the
proxy dials the backend from another host, list that address under `proxy-addresses`.

## Remove the old fixer plugins

Run only one forwarding plugin per backend. Remove any previous Waterdog/Waterbucket login
fixer (they install their own login handler) before installing this one.
