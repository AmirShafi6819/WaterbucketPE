import java

from helper import format_greeting

PlayerChatEvent = java.type(
    "dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent"
)


def on_startup(plugin):
    logger.info("ExamplePython script loaded")


def on_enable(plugin):
    plugin.subscribe(PlayerChatEvent, on_player_chat)

    registered = plugin.register_command(
        "pyhello",
        "Example command from a Python plugin",
        "/pyhello",
        ["pyhi"],
        None,
        hello_command,
    )
    if not registered:
        logger.warning("Could not register /pyhello; the name is already in use")


def on_player_chat(event):
    logger.info(
        "{}: {}",
        event.getPlayer().getName(),
        event.getMessage(),
    )


def hello_command(sender, alias, args):
    sender.sendMessage(format_greeting("WaterbucketPE"))
    return True


def on_disable(plugin):
    logger.info("ExamplePython disabled")
