<?php

/*
 * WaterbucketForwarding - the backend-side half of the WaterbucketPE forwarding.
 *
 * The proxy embeds the player's real address and Xbox identity into the client data object
 * of the login packet. This plugin makes a PocketMine-MP backend read them:
 *
 *  - the client data object is parsed strictly by the server, so the forwarded keys would
 *    abort the login with `JSON property "Waterdog_IP" does not exist ... ClientData`;
 *    the login handler this plugin installs strips every key that is not a client data
 *    field before the mapping runs;
 *  - the forwarded address and XUID are applied to the session, so logs, bans and plugins
 *    see the player instead of the proxy's own dial address;
 *  - with require-proxy on, joins from addresses the proxy does not dial from are refused:
 *    the protocol-level Only Proxy Join, without firewall rules.
 *
 * Requirements: xbox-auth must be off (server.properties). The proxy re-signs the login
 * chain, so the backend cannot verify Mojang signatures behind it; that is the same trust
 * boundary every Bedrock proxy setup has. player.verify-xuid should be off too, for the
 * same reason.
 */

declare(strict_types=1);

namespace WaterbucketForwarding;

use pocketmine\event\Listener;
use pocketmine\event\server\DataPacketReceiveEvent;
use pocketmine\network\mcpe\JwtException;
use pocketmine\network\mcpe\JwtUtils;
use pocketmine\network\mcpe\protocol\LoginPacket;
use pocketmine\plugin\PluginBase;
use pocketmine\utils\TextFormat;

final class Main extends PluginBase implements Listener {

	private const DEFAULT_KICK_MESSAGE = "§cPlease connect through the WaterbucketPE proxy.";

	protected function onEnable(): void {
		$this->saveDefaultConfig();

		$server = $this->getServer();
		if ($server->getOnlineMode()) {
			$this->getLogger()->warning("xbox-auth is on in server.properties: the WaterbucketPE proxy re-signs the login "
				. "chain, so the backend cannot verify Mojang signatures behind it. Set xbox-auth=off and let the proxy "
				. "check the Xbox authentication (online_mode in the proxy config).");
		}
		if ($server->getConfigGroup()->getPropertyBool("player.verify-xuid", true)) {
			$this->getLogger()->warning("player.verify-xuid is on in pocketmine.yml: behind a proxy the XUID arrives "
				. "through the forwarded login data, so set player.verify-xuid=false.");
		}

		$server->getPluginManager()->registerEvents($this, $this);

		$mode = ((bool) $this->getConfig()->get("require-proxy", true))
			? "Only Proxy Join is active: joins from outside " . implode(", ", $this->proxyAddresses()) . " are refused"
			: "require-proxy is off: direct joins are accepted";
		$this->getLogger()->info("WaterbucketPE forwarding active - " . $mode . ".");
	}

	public function onPacketReceived(DataPacketReceiveEvent $event): void {
		$packet = $event->getPacket();
		if (!$packet instanceof LoginPacket) {
			return;
		}

		try {
			[, $claims, ] = JwtUtils::parse($packet->clientDataJwt);
		} catch (JwtException) {
			// A login the backend cannot even parse is its own business; the strict handler
			// reports it with the right error. Nothing to forward from it anyway.
			return;
		}

		$origin = $event->getOrigin();
		$realIp = isset($claims["Waterdog_IP"]) && is_string($claims["Waterdog_IP"]) ? $claims["Waterdog_IP"] : null;
		$xuid = isset($claims["Waterdog_XUID"]) && is_string($claims["Waterdog_XUID"]) ? $claims["Waterdog_XUID"] : null;

		if ((bool) $this->getConfig()->get("require-proxy", true) && !$this->isProxyAddress($origin->getIp())) {
			$this->getLogger()->info("Refused a direct join from " . $origin->getIp() . ": not a listed proxy address");
			$origin->disconnect((string) ($this->getConfig()->get("kick-message", self::DEFAULT_KICK_MESSAGE) ?: self::DEFAULT_KICK_MESSAGE));
			$event->cancel();
			return;
		}

		// The forwarded keys are unknown to the strict client data parser, so the login is
		// handled by a handler that strips them before mapping and applies the identity.
		$origin->setHandler(new ForwardingLoginPacketHandler($this->getServer(), $origin, $realIp, $xuid));
	}

	/**
	 * The configured proxy addresses, normalized the way the backend sees its own sockets
	 * (localhost resolves to loopback on every platform that matters here).
	 *
	 * @return string[]
	 */
	private function proxyAddresses(): array {
		$configured = $this->getConfig()->get("proxy-addresses", ["127.0.0.1"]);
		return array_map(
			fn ($address) => self::normalizeAddress((string) $address),
			is_array($configured) ? $configured : []
		);
	}

	private function isProxyAddress(string $ip): bool {
		$ip = self::normalizeAddress($ip);
		foreach ($this->proxyAddresses() as $address) {
			if ($address === $ip) {
				return true;
			}
		}
		return false;
	}

	private static function normalizeAddress(string $address): string {
		return strcasecmp($address, "localhost") === 0 || $address === "0.0.0.0" ? "127.0.0.1" : strtolower($address);
	}
}
