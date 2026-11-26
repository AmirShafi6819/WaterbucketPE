<?php

declare(strict_types=1);

namespace WaterbucketForwarding;

use Closure;
use JsonMapper;
use JsonMapper_Exception;
use pocketmine\network\mcpe\handler\LoginPacketHandler;
use pocketmine\network\mcpe\JwtException;
use pocketmine\network\mcpe\JwtUtils;
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\PacketHandlingException;
use pocketmine\network\mcpe\protocol\types\login\ClientData;
use pocketmine\player\PlayerInfo;
use pocketmine\player\XboxLivePlayerInfo;
use pocketmine\Server;
use pocketmine\utils\TextFormat;
use ReflectionClass;
use ReflectionProperty;

/**
 * The login handler that understands the WaterbucketPE forwarding: it parses the client data
 * object leniently (the forwarded keys are stripped instead of aborting the login), and it
 * hands the forwarded identity to the session through the same hooks the server's own login
 * handler uses.
 */
final class ForwardingLoginPacketHandler extends LoginPacketHandler {

        /**
         * @param string|null $realIp the player's real address from the Waterdog_IP claim, null when absent
         * @param string|null $xuid   the player's Xbox ID from the Waterdog_XUID claim, null when absent
         */
        public function __construct(
                Server $server,
                NetworkSession $session,
                ?string $realIp,
                ?string $xuid
        ) {
                $playerInfoConsumer = Closure::bind(function (PlayerInfo $info) use ($session, $realIp, $xuid): void {
                        if ($realIp !== null && $realIp !== "") {
                                // The socket shows the proxy's own dial address; the session carries the
                                // player's real address from here on, so logs, bans and plugins all see it.
                                $session->ip = $realIp;
                        }
                        if ($xuid !== null && $xuid !== "" && $info instanceof XboxLivePlayerInfo) {
                                // The proxy re-signed the chain, so the forwarded XUID is the only identity
                                // the backend has: the session info is rebuilt around it.
                                $info = new XboxLivePlayerInfo($xuid, $info->getUsername(), $info->getUuid(),
                                        $info->getSkin(), $info->getLocale(), $info->getExtraData());
                        }
                        $session->info = $info;
                        $session->logger->setPrefix($session->getLogPrefix());
                        $session->logger->info("Player: " . TextFormat::AQUA . $info->getUsername() . TextFormat::RESET
                                . ($realIp !== null && $realIp !== "" ? TextFormat::RESET . " (" . $realIp . ")" : ""));
                }, $this, $session);

                $authCallback = Closure::bind(function (bool $isAuthenticated, bool $authRequired, ?string $error, ?string $clientPubKey) use ($session): void {
                        // Behind the proxy the backend trusts the proxy's own authentication verdict
                        // (online_mode on the proxy), so the forwarded verdict is accepted as-is.
                        $session->setAuthenticationStatus(true, $authRequired, $error, $clientPubKey);
                }, $this, $session);

                parent::__construct($server, $session, $playerInfoConsumer, $authCallback);
        }

        /**
         * The forwarded claims travel inside the client data object, which the server maps
         * strictly: unknown keys abort the login with `JSON property "Waterdog_IP" does not
         * exist in object of type ...ClientData`. This override drops every key that is not a
         * ClientData field before the mapping runs, so the forwarded data reaches this handler
         * instead of killing the login.
         */
        protected function parseClientData(string $clientDataJwt): ClientData {
                try {
                        [, $claims, ] = JwtUtils::parse($clientDataJwt);
                } catch (JwtException $e) {
                        throw PacketHandlingException::wrap($e);
                }

                $mapper = new JsonMapper();
                $mapper->bEnforceMapType = false;
                $mapper->bExceptionOnMissingData = true;
                $mapper->bExceptionOnUndefinedProperty = true;
                try {
                        $knownKeys = array_map(
                                fn (ReflectionProperty $property) => $property->getName(),
                                (new ReflectionClass(ClientData::class))->getProperties()
                        );
                        foreach ($claims as $key => $value) {
                                if (!in_array($key, $knownKeys, true)) {
                                        unset($claims[$key]);
                                }
                        }
                        return $mapper->map($claims, new ClientData());
                } catch (JsonMapper_Exception $e) {
                        throw PacketHandlingException::wrap($e);
                }
        }
}
