<?php
// Test plugin: exercises lifecycle hooks, event subscription, commands and scheduling.

$state = array(
    'startup' => false,
    'enabled' => false,
    'disabled' => false,
    'events' => 0,
    'command' => '',
    'tasks' => 0
);

$solo_count = 0;

function wb_test_state() {
    global $state;
    return $state;
}

function on_startup($plugin) {
    global $state;
    $state['startup'] = true;
}

function on_enable($plugin) {
    global $state;
    $state['enabled'] = true;
    $plugin->subscribe('TestSyncEvent', function ($event) {
        global $state;
        $state['events'] = $state['events'] + 1;
        $event->setWbFlag(true);
    });
    $plugin->registerCommand('phphello', 'PHP test command', '/phphello', array('ph'), '', function ($sender, $alias, $args) {
        global $state;
        $state['command'] = 'phphello|' . implode(',', $args);
        return true;
    });
}

function on_disable($plugin) {
    global $state;
    $state['disabled'] = true;
}

function wb_test_install_broken_handler($plugin) {
    $plugin->subscribe('TestSyncEvent', function ($event) {
        throw new Exception('broken php handler');
    });
}

function wb_test_install_healthy_handler($plugin) {
    $plugin->subscribe('TestSyncEvent', function ($event) {
        $event->setHealthyFlag(true);
    });
}

function wb_test_install_solo_handler($plugin) {
    $plugin->subscribe('TestSyncEvent', function ($event) {
        global $solo_count;
        $solo_count = $solo_count + 1;
    });
}

function wb_test_reset_solo() {
    global $solo_count;
    $solo_count = 0;
    return $solo_count;
}

function wb_test_read_solo() {
    global $solo_count;
    return $solo_count;
}

function wb_test_schedule_tasks($plugin) {
    $plugin->scheduleAsync(function () {
        global $state;
        $state['tasks'] = $state['tasks'] + 1;
    });
}
