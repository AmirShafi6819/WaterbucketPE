package pluginhost

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

type testLogger struct {
	lines []string
}

func (l *testLogger) Debug(msg string) {}
func (l *testLogger) Info(msg string)  { l.lines = append(l.lines, msg) }
func (l *testLogger) Warn(msg string)  { l.lines = append(l.lines, msg) }
func (l *testLogger) Error(msg string) { l.lines = append(l.lines, msg) }

func copyDir(t *testing.T, src, dst string) {
	t.Helper()
	if err := os.MkdirAll(dst, 0o755); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(src)
	for _, e := range entries {
		data, err := os.ReadFile(filepath.Join(src, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dst, e.Name()), data, 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestLoadGoodPlugin(t *testing.T) {
	root := t.TempDir()
	copyDir(t, "testdata/goodplugin", filepath.Join(root, "TestGuard"))

	api := &pluginapi.API{
		Worlds: &fakeWorlds{},
		Proxy:  &fakeProxy{},
		Log:    &testLogger{},
	}
	host := NewHost(&testLogger{}, api, root)
	host.LoadAll()

	status := host.Status()
	if len(status) != 1 {
		t.Fatalf("want 1 plugin, got %d", len(status))
	}
	if status[0].State != bridge.PluginEnabled {
		t.Fatalf("plugin not enabled: %+v", status[0])
	}
	if status[0].APIVersion != "1.6.0" {
		t.Fatalf("api version wrong: %q", status[0].APIVersion)
	}
	if host.PluginCount() != 1 {
		t.Fatal("plugin count wrong")
	}

	// Events reach the interpreted plugin and it answers using the API.
	cancelled := host.Dispatch(&pluginapi.BlockBreakEvent{
		ServerKey: "srv", Dim: 0,
		Player: &pluginapi.Player{Entity: pluginapi.Entity{IsPlayer: true, Name: "Steve"}},
		Pos:    pluginapi.PosI{X: 1, Y: 64, Z: 2},
	})
	// The fake world reports stone: not bedrock, so no cancel.
	if cancelled {
		t.Fatal("stone break was cancelled")
	}

	// UnloadAll runs OnDisable without panicking.
	host.UnloadAll()
	if host.Status()[0].State != bridge.PluginDisabled {
		t.Fatal("plugin not disabled")
	}
}

func TestLoadLegacyAndRejectBadAPI(t *testing.T) {
	root := t.TempDir()
	copyDir(t, "testdata/legacyplugin", filepath.Join(root, "LegacyPlugin"))
	copyDir(t, "testdata/badplugin", filepath.Join(root, "BadApi"))

	host := NewHost(&testLogger{}, &pluginapi.API{Worlds: &fakeWorlds{}, Proxy: &fakeProxy{}, Log: &testLogger{}}, root)
	host.LoadAll()

	status := host.Status()
	if len(status) != 2 {
		t.Fatalf("want 2 plugin entries, got %d", len(status))
	}
	byName := map[string]bridge.PluginStatusEntry{}
	for _, s := range status {
		byName[s.Name] = s
	}
	if legacy, ok := byName["LegacyPlugin"]; !ok || legacy.State != bridge.PluginEnabled {
		t.Fatalf("legacy plugin must load: %+v", status)
	}
	if bad, ok := byName["BadApi"]; !ok || bad.State != bridge.PluginFailed {
		t.Fatalf("api-version 2.0.0 plugin must be rejected: %+v", status)
	}
	if bad, ok := byName["BadApi"]; ok && bad.Error == "" {
		t.Fatal("rejection must carry the reason")
	}
}

func TestValidateAPIVersion(t *testing.T) {
	for _, ok := range []string{"1.6.0", "1.0.0", ""} {
		if err := ValidateAPIVersion(ok); err != nil {
			t.Fatalf("api-version %q must be accepted: %v", ok, err)
		}
	}
	for _, bad := range []string{"2.0.0", "1.5.6", "0.9.9"} {
		if err := ValidateAPIVersion(bad); err == nil {
			t.Fatalf("api-version %q must be rejected", bad)
		}
	}
}

func TestPanickingHandlerFailsOpen(t *testing.T) {
	root := t.TempDir()
	copyDir(t, "testdata/goodplugin", filepath.Join(root, "TestGuard"))
	host := NewHost(&testLogger{}, &pluginapi.API{Worlds: &fakeWorlds{}, Proxy: &fakeProxy{}, Log: &testLogger{}}, root)
	host.LoadAll()
	// A handler panic must never poison the dispatch loop: a nil deref inside
	// the event stays recovered, dispatch continues.
	cancelled := host.Dispatch(&pluginapi.WorldUnloadEvent{ServerKey: "x"})
	if cancelled {
		t.Fatal("observation event must not cancel")
	}
}

// ---- fakes for the facades ----

type fakeWorlds struct{}

func (f *fakeWorlds) ListWorlds() []pluginapi.World {
	return []pluginapi.World{{Key: "srv", Name: "world"}}
}
func (f *fakeWorlds) GetWorld(key string) *pluginapi.World {
	w := pluginapi.World{Key: key, Name: "world"}
	return &w
}
func (f *fakeWorlds) GetBlock(serverKey string, dim int32, x, y, z int32) pluginapi.Block {
	return pluginapi.Block{Name: "minecraft:stone", Position: pluginapi.PosI{X: x, Y: y, Z: z}}
}
func (f *fakeWorlds) IsChunkLoaded(serverKey string, dim, cx, cz int32) bool { return true }
func (f *fakeWorlds) GetChunk(serverKey string, dim, cx, cz int32) (*pluginapi.Chunk, bool) {
	return &pluginapi.Chunk{Dim: dim, CX: cx, CZ: cz}, true
}
func (f *fakeWorlds) GetBlockEntity(serverKey string, dim int32, x, y, z int32) (*pluginapi.BlockEntity, bool) {
	return nil, false
}
func (f *fakeWorlds) GetEntity(serverKey string, eid int64) (*pluginapi.Entity, bool) {
	return nil, false
}
func (f *fakeWorlds) GetEntitiesNear(serverKey string, dim int32, x, y, z, radius float32, limit int) []*pluginapi.Entity {
	return nil
}
func (f *fakeWorlds) GetPlayer(serverKey string, eid int64) (*pluginapi.Player, bool) {
	return nil, false
}
func (f *fakeWorlds) GetPlayerByName(serverKey, name string) (*pluginapi.Player, bool) {
	p := &pluginapi.Player{Entity: pluginapi.Entity{IsPlayer: true, Name: name}}
	return p, true
}
func (f *fakeWorlds) GetInventory(serverKey string, eid int64, windowID int32) ([]pluginapi.Item, bool) {
	return nil, false
}

type fakeProxy struct{}

func (f *fakeProxy) SendMessage(playerName, message string) error           { return nil }
func (f *fakeProxy) SendTip(playerName, message string) error               { return nil }
func (f *fakeProxy) SendPopup(playerName, message, subtitle string) error   { return nil }
func (f *fakeProxy) SendTitle(playerName, title, subtitle string) error     { return nil }
func (f *fakeProxy) Kick(playerName, reason string) error                   { return nil }
func (f *fakeProxy) Transfer(playerName, serverName string) error           { return nil }
func (f *fakeProxy) BroadcastMessage(message string) error                  { return nil }
func (f *fakeProxy) DispatchCommand(commandLine string) error               { return nil }
func (f *fakeProxy) DispatchCommandAs(playerName, commandLine string) error { return nil }
func (f *fakeProxy) GetOnlinePlayers() []string                             { return []string{"Steve"} }
func (f *fakeProxy) OnlineCount() int                                       { return 1 }
func (f *fakeProxy) ProxyVersion() string                                   { return "1.6.0" }
