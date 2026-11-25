package world

import (
	"testing"
	"time"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
)

func TestEntityAndPlayerLifecycle(t *testing.T) {
	reg := NewRegistry(300, 1024)

	reg.ApplyPlayerInfo("srv", 42, "Steve", "xuid-1", 0, 0)
	reg.ApplyEntityMove("srv", 42, 10.5, 64, 20.25, 90, -10, true)
	reg.ApplyEntityAttribute("srv", 42, "minecraft:health", 15)
	reg.ApplyEntityMeta("srv", 42, 1<<1) // flag 1 = sneaking

	p, ok := reg.PlayerQuery("srv", 42)
	if !ok {
		t.Fatal("player not tracked")
	}
	if p.Name != "Steve" || p.Pos[0] != 10.5 || p.Attrs["minecraft:health"] != 15 {
		t.Fatalf("player state wrong: %+v", p)
	}
	if !p.Entity.IsPlayer {
		t.Fatal("IsPlayer not set")
	}

	// Zombies appear and move.
	reg.ApplyEntityAdd("srv", 0, 100, "minecraft:zombie", 1, 2, 3, 0, 0, [3]float32{}, false, "", "", 0)
	e, ok := reg.EntityQuery("srv", 100)
	if !ok || e.Type != "minecraft:zombie" {
		t.Fatal("entity not tracked")
	}

	// Entities near: radius 50 around the zombie catches it, not Steve at 10/64/20.
	near := reg.EntitiesNear("srv", 0, 1, 2, 3, 50, 0)
	if len(near) != 1 || near[0].Eid != 100 {
		t.Fatalf("near query wrong: %+v", near)
	}

	// Removal cleans both maps.
	reg.ApplyEntityRemove("srv", 100)
	if _, ok := reg.EntityQuery("srv", 100); ok {
		t.Fatal("entity survived removal")
	}
}

func TestInventoryTracking(t *testing.T) {
	reg := NewRegistry(300, 1024)
	reg.ApplyPlayerInfo("srv", 7, "Alex", "xuid-2", 1, 0)

	reg.ApplyInventorySlot("srv", 7, 12, 0, blockItem("minecraft:dirt", 64))
	reg.ApplyInventorySlot("srv", 7, 12, 8, blockItem("minecraft:torch", 16))
	inv, ok := reg.InventoryQuery("srv", 7, 12)
	if !ok || len(inv) != 9 {
		t.Fatalf("inventory shape wrong: %v", ok)
	}
	if inv[0].Identifier != "minecraft:dirt" || inv[0].Count != 64 {
		t.Fatalf("slot 0 wrong: %+v", inv[0])
	}
	if inv[8].Identifier != "minecraft:torch" {
		t.Fatalf("slot 8 wrong: %+v", inv[8])
	}

	// Equip updates hotbar + held item.
	reg.ApplyEquip("srv", 7, blockItem("minecraft:iron_sword", 1), 2, 2)
	p, _ := reg.PlayerQuery("srv", 7)
	if p.HeldItem.Identifier != "minecraft:iron_sword" {
		t.Fatalf("held item wrong: %+v", p.HeldItem)
	}
	hotbar, _ := reg.InventoryQuery("srv", 7, windowHotbar)
	if hotbar[2].Identifier != "minecraft:iron_sword" {
		t.Fatalf("hotbar slot wrong: %+v", hotbar[2])
	}

	// Whole-window content replaces slots.
	contents := map[int32]bridge.Item{
		0: {Identifier: "minecraft:stone", Count: 1},
	}
	reg.ApplyInventoryContent("srv", 7, 12, contents)
	inv, _ = reg.InventoryQuery("srv", 7, 12)
	if inv[0].Identifier != "minecraft:stone" || inv[8].Identifier != "minecraft:torch" {
		t.Fatalf("content update wrong: %+v / %+v", inv[0], inv[8])
	}
}

// blockItem builds a test item.
func blockItem(name string, count int32) bridge.Item {
	return bridge.Item{Identifier: name, Count: count}
}

func TestWorldMetaAndRetention(t *testing.T) {
	reg := NewRegistry(1, 100) // 1 second retention for a fast test
	reg.ApplyWorldMeta("srv", "overworld", 12345, BlockPos{0, 64, 0}, 0, 2,
		map[string]string{"doDaylightCycle": "true"}, nil)
	reg.ApplyTime("srv", 6000)
	reg.ApplyGameRule("srv", "keepInventory", "true")
	reg.ApplyWeather("srv", true, false)

	worldRef := reg.World("srv")
	if worldRef == nil || worldRef.Name != "overworld" || worldRef.Seed != 12345 {
		t.Fatalf("world meta wrong: %+v", worldRef)
	}
	if worldRef.Time != 6000 {
		t.Fatalf("time wrong: %d", worldRef.Time)
	}
	if worldRef.Gamerules["keepInventory"] != "true" {
		t.Fatalf("gamerule wrong: %+v", worldRef.Gamerules)
	}
	if !worldRef.Raining || worldRef.Thunder {
		t.Fatalf("weather wrong: %+v", worldRef)
	}

	summaries := reg.WorldList()
	if len(summaries) != 1 || summaries[0].Key != "srv" {
		t.Fatalf("world list wrong: %+v", summaries)
	}

	// Server down drops the world entirely.
	reg.ApplyServerDown("srv")
	if reg.World("srv") != nil {
		t.Fatal("world survived server down")
	}
}

func TestSweepEvictsStaleChunks(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	var blob []byte
	blob = append(blob, buildSubchunkV9(0, [][]byte{air}, make([]uint16, 4096), 4)...)

	reg := NewRegistry(1, 100) // 1s retention
	reg.ApplyChunk("srv", 0, 0, 0, blob)
	if !reg.ChunkLoaded("srv", 0, 0, 0) {
		t.Fatal("chunk not loaded")
	}
	time.Sleep(1100 * time.Millisecond)
	evicted := reg.Sweep()
	if evicted == 0 || reg.ChunkLoaded("srv", 0, 0, 0) {
		t.Fatalf("sweep failed: evicted=%d loaded=%v", evicted, reg.ChunkLoaded("srv", 0, 0, 0))
	}
}

func TestMaxChunksLRU(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	var blob []byte
	blob = append(blob, buildSubchunkV9(0, [][]byte{air}, make([]uint16, 4096), 4)...)

	reg := NewRegistry(3600, 3)
	for i := 0; i < 5; i++ {
		reg.ApplyChunk("srv", 0, int32(i), 0, blob)
		time.Sleep(2 * time.Millisecond)
	}
	reg.Sweep()
	// Cap 3: oldest two chunks must be gone.
	if reg.ChunkLoaded("srv", 0, 0, 0) || reg.ChunkLoaded("srv", 0, 1, 0) {
		t.Fatal("oldest chunks survived the cap")
	}
	if !reg.ChunkLoaded("srv", 0, 4, 0) {
		t.Fatal("newest chunk was evicted")
	}
}

func BenchmarkBlockQuery(b *testing.B) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	stone := buildPaletteNBT("minecraft:stone", nil, 1769472)
	var blob []byte
	for i := 0; i < 16; i++ {
		indices := make([]uint16, 4096)
		indices[i] = 1
		blob = append(blob, buildSubchunkV9(uint8(i), [][]byte{air, stone}, indices, 4)...)
	}
	reg := NewRegistry(3600, 4096)
	reg.ApplyChunk("srv", 0, 0, 0, blob)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = reg.BlockQuery("srv", 0, int32(i%16), int32(i/16%16), 5)
	}
}
