<?php
// Compatibility-surface test plugin: $pmmp global, compat events, compat Player wrapper.

$state = array(
    'startup' => false,
    'server_name' => '',
    'chat_seen' => '',
    'player_msg' => false
);

function wb_pmmp_state() {
    global $state;
    return $state;
}

function on_startup($plugin) {
    global $state, $compat;
    $state['startup'] = true;
    // The facade is live from the first hook; simple event names resolve the compat package too
    $server = $compat->getServer();
    $state['server_name'] = $server->getName();
    $state['api_version'] = $server->getApiVersion();
    $plugin->subscribe('dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent', function ($event) {
        global $state;
        $state['chat_seen'] = $event->getMessage();
        $event->getPlayer()->sendMessage('seen: ' . $state['chat_seen']);
        $state['player_msg'] = true;
        $event->setMessage($event->getMessage() . ' [compat]');
    });
}
