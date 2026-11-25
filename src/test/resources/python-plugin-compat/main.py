import java

state = java.type("java.util.HashMap")()
state.put("startup", False)
state.put("server_name", None)
state.put("api_version", None)
state.put("chat_seen", None)
state.put("player_msg", False)


def on_startup(plugin):
    state.put("startup", True)
    # The compatibility facade is live from the first hook
    server = compat.getServer()
    state.put("server_name", server.getName())
    state.put("api_version", server.getApiVersion())
    plugin.subscribe(compat.playerChatEvent(), handle_compat_chat)


def handle_compat_chat(event):
    state.put("chat_seen", event.getMessage())
    # The compatibility Player wrapper works from Python and actually messages the client
    event.getPlayer().sendMessage("seen: " + event.getMessage())
    state.put("player_msg", True)
    # editing the message must flow back into the proxy pipeline
    event.setMessage(event.getMessage() + " [compat]")


def get_state():
    return state
