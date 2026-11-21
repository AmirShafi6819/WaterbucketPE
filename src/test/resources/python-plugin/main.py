# Sample Python plugin exercising the Waterbucket plugin bridge.
import java
import helper

state = java.type("java.util.HashMap")()
state.put("startup", False)
state.put("enabled", False)
state.put("disabled", False)
state.put("events", 0)
state.put("flag", False)
state.put("command", None)


def on_startup(plugin):
    state.put("startup", True)


def on_enable(plugin):
    state.put("enabled", True)
    helper.mark_ready(state)
    SyncEvent = java.type("dev.waterbucket.waterbucketpe.plugin.python.PythonPluginTest$TestSyncEvent")
    plugin.subscribe(SyncEvent, handle_sync_event)
    plugin.register_command("pyhello", "Hello from Python", "/pyhello", None, None, run_pyhello)


def handle_sync_event(event):
    state.put("events", state.get("events") + 1)
    event.setWbFlag(True)


def run_pyhello(sender, alias, args):
    state.put("command", "%s|%s" % (alias, ",".join(args)))
    return True


def on_disable(plugin):
    state.put("disabled", True)


def get_state():
    return state


def bump(n):
    # used by the concurrency test
    total = 0
    for i in range(n):
        total += 1
    return total


def install_broken_handler():
    SyncEvent = java.type("dev.waterbucket.waterbucketpe.plugin.python.PythonPluginTest$TestSyncEvent")
    plugin.subscribe(SyncEvent, broken_handler)


def broken_handler(event):
    raise RuntimeError("boom from python")


def install_healthy_handler():
    SyncEvent = java.type("dev.waterbucket.waterbucketpe.plugin.python.PythonPluginTest$TestSyncEvent")
    plugin.subscribe(SyncEvent, healthy_handler)


def healthy_handler(event):
    event.setHealthyFlag(True)


def install_counting_handler():
    global counter
    counter = java.type("java.util.concurrent.atomic.AtomicInteger")()
    SyncEvent = java.type("dev.waterbucket.waterbucketpe.plugin.python.PythonPluginTest$TestSyncEvent")
    plugin.subscribe(SyncEvent, counting_handler)


def counting_handler(event):
    counter.incrementAndGet()


def get_counted():
    return counter.get()


def reset_events():
    state.put("events", 0)
