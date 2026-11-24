<?php
/**
 * WaterbucketPE PHP plugin example.
 *
 * The script runs inside the embedded Quercus PHP runtime with three globals already bound:
 *   $plugin - this plugin (subscribe/registerCommand/schedule* live here)
 *   $proxy  - the ProxyServer (players, servers, config, max players ...)
 *   $logger - this plugin's logger
 *
 * Java interop works through PHP object syntax: any bound Java object's methods can be
 * called directly, e.g. $proxy->getAllPlayers() or $event->getPlayer()->getName().
 */

function on_startup($plugin)
{
    global $logger;
    $logger->info('ExamplePHP script loaded (Quercus PHP runtime)');
}

function on_enable($plugin)
{
    global $logger;

    // Subscribe to a proxy event by its class name (simple name or FQCN).
    // Synchronous handlers run inline, exactly like Java listeners.
    $plugin->subscribe('PlayerChatEvent', function ($event) use ($logger) {
        $logger->info($event->getPlayer()->getName() . ': ' . $event->getMessage());
    });

    // Register a console/player command. The executor returns true when handled.
    $registered = $plugin->registerCommand(
        'phhello',
        'Example command from a PHP plugin',
        '/phhello',
        array('phhi'),
        '',
        function ($sender, $alias, $args) use ($proxy) {
            $count = count($proxy->getAllPlayers());
            $sender->sendMessage('Hello from ExamplePHP! ' . $count . ' player(s) online.');
            return true;
        }
    );
    if (!$registered) {
        $logger->warning('Could not register /phhello; the name is already in use');
    }

    // Recurring async work: runs every 20 ticks (1s), cancelled automatically on disable.
    $ticks = 0;
    $plugin->scheduleRepeating(function () use (&$ticks, $logger) {
        $ticks++;
        if ($ticks === 60) {
            $logger->info('ExamplePHP has been running for a minute');
        }
    }, 20);
}

function on_disable($plugin)
{
    global $logger;
    $logger->info('ExamplePHP disabled; subscriptions, commands and tasks are detached automatically');
}
