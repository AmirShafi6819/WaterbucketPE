package runtimeapp

import (
	"testing"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

func newTestApp(t *testing.T) *App {
	t.Helper()
	return NewApp(&Config{WorldView: true, Retention: 300, MaxChunks: 1024, PluginsDir: t.TempDir()}, func(level int, msg string) {})
}

// TestBuildEventsCoverEveryWireEvent pins that every wire event id maps to a
// plugin event struct - a missing case would silently swallow events.
func TestBuildEventsCoverEveryWireEvent(t *testing.T) {
	app := newTestApp(t)
	for _, id := range []uint16{
		bridge.EventWorldInit, bridge.EventWorldUnload, bridge.EventChunkSend,
		bridge.EventTimeChange, bridge.EventWeatherChange, bridge.EventDifficultyChange,
		bridge.EventGameRuleChange, bridge.EventSpawnPositionChange,
		bridge.EventBlockBreak, bridge.EventBlockPlace, bridge.EventBlockStartBreak,
		bridge.EventBlockUpdate, bridge.EventSignChange,
		bridge.EventEntitySpawn, bridge.EventEntityDespawn, bridge.EventEntityMove,
		bridge.EventEntityMotion, bridge.EventEntityEffect, bridge.EventEntityHealthChange,
		bridge.EventEntityDamage, bridge.EventEntityRegainHealth, bridge.EventEntityDataUpdate,
		bridge.EventEntityTeleport,
		bridge.EventPlayerInteract, bridge.EventPlayerItemHeld, bridge.EventPlayerDropItem,
		bridge.EventPlayerToggleSneak, bridge.EventPlayerToggleSprint, bridge.EventPlayerAnimation,
		bridge.EventPlayerDeath, bridge.EventPlayerRespawn, bridge.EventPlayerGameModeChange,
		bridge.EventPlayerChangeDimension, bridge.EventPlayerFormRespond,
		bridge.EventPlayerInteractEntity, bridge.EventPlayerMouseOver,
		bridge.EventPlayerBedEnter, bridge.EventPlayerBedLeave,
		bridge.EventInventoryOpen, bridge.EventInventoryClose, bridge.EventInventoryUpdate,
		bridge.EventCraftItem, bridge.EventInventoryTransaction,
	} {
		fields := []bridge.Field{{Type: bridge.TypeStr, Str: "srv"}, {Type: bridge.TypeI32, I32: 0},
			{Type: bridge.TypeStr, Str: "Steve"}}
		event := app.buildEvent(id, fields)
		if event == nil {
			t.Fatalf("wire event %d has no plugin event mapping", id)
		}
		if event.EventName() == "" {
			t.Fatalf("event %d has empty name", id)
		}
	}
}

func TestBuildBlockBreakResolvesPlayerAndBlock(t *testing.T) {
	app := newTestApp(t)
	// Track the player first so the event resolves them.
	app.registry.ApplyPlayerInfo("srv", 5, "Steve", "xuid-9", 0, 0)

	fields := []bridge.Field{
		{Type: bridge.TypeStr, Str: "srv"},
		{Type: bridge.TypeI32, I32: 0},
		{Type: bridge.TypeStr, Str: "Steve"},
		{Type: bridge.TypeVec3i, Xi: 10, Yi: 64, Zi: -5},
		{Type: bridge.TypeItem, ItemVal: bridge.Item{Identifier: "minecraft:diamond_pickaxe", Count: 1}},
		{Type: bridge.TypeI32, I32: 5},
	}
	event, ok := app.buildEvent(bridge.EventBlockBreak, fields).(*pluginapi.BlockBreakEvent)
	if !ok {
		t.Fatalf("wrong event type: %T", app.buildEvent(bridge.EventBlockBreak, fields))
	}
	if event.Player == nil || event.Player.Name != "Steve" {
		t.Fatalf("player not resolved: %+v", event.Player)
	}
	if event.Pos.X != 10 || event.Pos.Z != -5 {
		t.Fatalf("pos wrong: %+v", event.Pos)
	}
	if event.Item.Identifier != "minecraft:diamond_pickaxe" {
		t.Fatalf("item wrong: %+v", event.Item)
	}
	if event.Face != 5 {
		t.Fatalf("face wrong: %d", event.Face)
	}
	if event.EventName() != "BlockBreakEvent" {
		t.Fatalf("name wrong: %s", event.EventName())
	}
}

func TestSignChangeEventParsesLines(t *testing.T) {
	app := newTestApp(t)
	lines := "line1\x00line2\x00\x00"
	fields := []bridge.Field{
		{Type: bridge.TypeStr, Str: "srv"},
		{Type: bridge.TypeI32, I32: 0},
		{Type: bridge.TypeStr, Str: "Alex"},
		{Type: bridge.TypeVec3i, Xi: 1, Yi: 2, Zi: 3},
		{Type: bridge.TypeBytes, Bytes: []byte(lines)},
	}
	event, ok := app.buildEvent(bridge.EventSignChange, fields).(*pluginapi.SignChangeEvent)
	if !ok {
		t.Fatal("wrong event type")
	}
	if event.Lines[0] != "line1" || event.Lines[1] != "line2" || event.Lines[2] != "" {
		t.Fatalf("lines wrong: %+v", event.Lines)
	}
}

func TestHandleDeltaAppliesWorldMetaAndChunk(t *testing.T) {
	app := newTestApp(t)

	w := bridge.NewWriter(256)
	w.Byte(bridge.DeltaWorldMeta)
	w.String("lobby")
	w.String("world")
	w.Int64(12345)
	w.Vec3i(0, 64, 0)
	w.Int32(0)
	w.Int32(2)
	w.Uint16(1)
	w.String("keepInventory")
	w.String("true")
	w.Uint16(1)
	w.Int32(77)
	w.String("minecraft:custom_block")
	if err := app.handleDelta(w.Buf); err != nil {
		t.Fatalf("world meta delta: %v", err)
	}
	worldRef := app.registry.World("lobby")
	if worldRef == nil || worldRef.Name != "world" || worldRef.Seed != 12345 {
		t.Fatalf("world meta not applied: %+v", worldRef)
	}
	if worldRef.Gamerules["keepInventory"] != "true" {
		t.Fatalf("gamerule not applied")
	}
	if len(worldRef.BlockTable) != 1 || worldRef.BlockTable[77].Name != "minecraft:custom_block" {
		t.Fatalf("block table not applied")
	}

	// Chunk delta: a v1 air-only blob.
	w2 := bridge.NewWriter(64)
	w2.Byte(bridge.DeltaChunk)
	w2.String("lobby")
	w2.Int32(0)
	w2.Int32(1)
	w2.Int32(-1)
	w2.Bytes([]byte{0x01, 4, 0, 0, 0})
	if err := app.handleDelta(w2.Buf); err != nil {
		t.Fatalf("chunk delta: %v", err)
	}
	if !app.registry.ChunkLoaded("lobby", 0, 1, -1) {
		t.Fatal("chunk not loaded after delta")
	}

	// Query the world list through the query path.
	w3 := bridge.NewWriter(32)
	w3.Byte(bridge.OpQuery)
	w3.Uint32(1)
	w3.Byte(QueryWorldList)
	if err := app.handleQuery(w3.Buf[1:]); err != nil {
		t.Fatalf("query: %v", err)
	}
}

func TestEventSyncReturnsCancelledVerdict(t *testing.T) {
	app := newTestApp(t)
	// No plugins loaded: sync dispatch must answer "not cancelled" and never
	// block the proxy packet thread.
	w := bridge.NewWriter(64)
	fields := []bridge.Field{
		{Type: bridge.TypeStr, Str: "srv"},
		{Type: bridge.TypeI32, I32: 0},
		{Type: bridge.TypeStr, Str: "Steve"},
		{Type: bridge.TypeVec3i, Xi: 1, Yi: 2, Zi: 3},
	}
	bridge.EncodeEvent(w, bridge.OpEventSync, 7, bridge.EventBlockBreak, fields)
	if err := app.handleFrame(w.Buf); err != nil {
		t.Fatalf("event sync frame: %v", err)
	}
}

func TestPluginStatusEncodingHasAllStates(t *testing.T) {
	w := bridge.NewWriter(128)
	bridge.EncodePluginStatus(w, []bridge.PluginStatusEntry{
		{Name: "a", Version: "1.0.0", APIVersion: "1.6.0", State: bridge.PluginEnabled},
		{Name: "b", Version: "0.9", APIVersion: "1.0.0", State: bridge.PluginFailed, Error: "boom"},
	})
	r := bridge.NewReader(w.Buf)
	if op, _ := r.Byte(); op != bridge.OpPluginStatus {
		t.Fatal("opcode wrong")
	}
	count, _ := r.Uint16()
	if count != 2 {
		t.Fatalf("count wrong: %d", count)
	}
	for _, want := range []string{"a", "b"} {
		name, _ := r.String()
		if name != want {
			t.Fatalf("name wrong: %s", name)
		}
		_, _ = r.String() // version
		_, _ = r.String() // api version
		state, _ := r.Byte()
		_, _ = r.String() // error
		if state != bridge.PluginEnabled && state != bridge.PluginFailed {
			t.Fatalf("state wrong: %d", state)
		}
	}
}

func TestToAPIItemParsesNBT(t *testing.T) {
	// NBT: compound root with display.Name string.
	var nbt []byte
	nbt = append(nbt, 10)    // compound
	nbt = append(nbt, 0)     // empty root name
	nbt = append(nbt, 10, 7) // compound tag "display"
	nbt = append(nbt, []byte("display")...)
	nbt = append(nbt, 8, 4) // string tag "Name"
	nbt = append(nbt, []byte("Name")...)
	nbt = append(nbt, 5) // value len 5
	nbt = append(nbt, []byte("Sword")...)
	nbt = append(nbt, 0) // end display
	nbt = append(nbt, 0) // end root

	item := toAPIItem(bridge.Item{Identifier: "minecraft:iron_sword", Count: 1, HasNBT: true, NBT: nbt})
	if item.CustomName() != "Sword" {
		t.Fatalf("custom name wrong: %q", item.CustomName())
	}
}
