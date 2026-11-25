// Package pluginapi is the public Go plugin API of the WaterbucketPE proxy.
// Plugins written in Go import this package, implement the Plugin interface
// and are loaded by the Go runtime sidecar. Everything the proxy observed of
// the downstream worlds - blocks, entities, players, inventories - is
// reachable here with real live data; nothing is a stub.
package pluginapi

// Vec3 is a floating point world position or vector.
type Vec3 struct {
	X, Y, Z float32
}

// PosI is an integer block position.
type PosI struct {
	X, Y, Z int32
}

// Item is one inventory item as the proxy observed it.
type Item struct {
	Identifier string
	Damage     int32
	Count      int32
	NetID      int64
	NBT        map[string]interface{}
}

// IsAir reports whether the item is empty.
func (i Item) IsAir() bool { return i.Identifier == "" && i.Count == 0 }

// Name returns the identifier, or "Air" when empty.
func (i Item) Name() string {
	if i.Identifier == "" {
		return "Air"
	}
	return i.Identifier
}

// CustomName reads the display name from item NBT when present.
func (i Item) CustomName() string {
	display, ok := i.NBT["display"].(map[string]interface{})
	if !ok {
		return ""
	}
	if name, ok := display["Name"].(string); ok {
		return name
	}
	return ""
}

// Enchantments parses the stored enchantment list: a slice of maps with id and lvl.
func (i Item) Enchantments() []map[string]interface{} {
	list, ok := i.NBT["ench"].([]interface{})
	if !ok {
		return nil
	}
	out := make([]map[string]interface{}, 0, len(list))
	for _, entry := range list {
		if m, ok := entry.(map[string]interface{}); ok {
			out = append(out, m)
		}
	}
	return out
}

// Block is one block as resolved from the live world model.
type Block struct {
	Name       string
	RuntimeID  int32
	Position   PosI
	Properties map[string]string
}

// IsAir reports whether the block position holds air (or is not loaded).
func (b Block) IsAir() bool {
	return b.Name == "minecraft:air" || b.Name == ""
}

// BlockEntity is a parsed block entity: signs, chests, spawners and friends.
type BlockEntity struct {
	Type     string
	Position PosI
	NBT      map[string]interface{}
}

// SignText returns the text lines of a sign block entity (front side first).
// It understands the modern FrontText/BackText layout and the legacy Text key.
func (b BlockEntity) SignText() []string {
	lines := []string{}
	if front, ok := b.NBT["FrontText"].(map[string]interface{}); ok {
		if msg, ok := front["Text"].(string); ok && msg != "" {
			lines = append(lines, msg)
		}
	}
	if legacy, ok := b.NBT["Text"].(string); ok && legacy != "" && len(lines) == 0 {
		lines = append(lines, legacy)
	}
	if back, ok := b.NBT["BackText"].(map[string]interface{}); ok {
		if msg, ok := back["Text"].(string); ok && msg != "" {
			lines = append(lines, msg)
		}
	}
	if lines == nil {
		return []string{}
	}
	return lines
}

// Entity flag ordinals, matching the Bedrock EntityFlags bit layout.
const (
	FlagOnFire    = 0
	FlagSneaking  = 1
	FlagRiding    = 2
	FlagSprinting = 3
	FlagUsingItem = 4
	FlagInvisible = 5
	FlagInAir     = 7
	FlagNoAI      = 8
	FlagBreathing = 9
	FlagInWall    = 10
	FlagSwimming  = 20
	FlagGliding   = 23
)

// Entity is every tracked entity snapshot: mobs, items, projectiles, players.
type Entity struct {
	Eid              int64
	Type             string
	Dim              int32
	X, Y, Z          float32
	MotX, MotY, MotZ float32
	Yaw              float32
	Pitch            float32
	Flags            uint64
	Attrs            map[string]float32
	IsPlayer         bool
	Name             string
	Xuid             string
}

// Flag reports one boolean entity flag by ordinal.
func (e Entity) Flag(flag uint32) bool {
	return e.Flags&(1<<flag) != 0
}

// Health returns the tracked health attribute value.
func (e Entity) Health() float32 {
	if e.Attrs == nil {
		return -1
	}
	return e.Attrs["minecraft:health"]
}

// MaxHealth returns the tracked maximum health attribute.
func (e Entity) MaxHealth() float32 {
	if e.Attrs == nil {
		return -1
	}
	return e.Attrs["minecraft:health.max"]
}

// Position returns the entity position.
func (e Entity) Position() Vec3 {
	return Vec3{X: e.X, Y: e.Y, Z: e.Z}
}

// Motion returns the entity motion vector.
func (e Entity) Motion() Vec3 {
	return Vec3{X: e.MotX, Y: e.MotY, Z: e.MotZ}
}

// Player is a tracked player on a downstream server.
type Player struct {
	Entity
	Gamemode      int32
	HeldItem      Item
	WindowID      int32 // active inventory window, -1 when closed
	WorldKeyField string
}

// Gamemode name constants, matching the original software's numbering.
const (
	GameModeSurvival  = 0
	GameModeCreative  = 1
	GameModeAdventure = 2
	GameModeSpectator = 3
)

// GameModeName renders a gamemode number as its name.
func GameModeName(mode int32) string {
	switch mode {
	case GameModeSurvival:
		return "Survival"
	case GameModeCreative:
		return "Creative"
	case GameModeAdventure:
		return "Adventure"
	case GameModeSpectator:
		return "Spectator"
	default:
		return "Unknown"
	}
}

// Inventory is one observed window of a player's inventory.
type Inventory struct {
	WindowID int32
	Items    []Item
	Holder   *Player
}

// Size reports the number of observed slots.
func (inv Inventory) Size() int { return len(inv.Items) }

// Get returns the item in one slot, or an air item.
func (inv Inventory) Get(slot int) Item {
	if slot < 0 || slot >= len(inv.Items) {
		return Item{}
	}
	return inv.Items[slot]
}

// Chunk is a view of one loaded chunk in the live world model.
type Chunk struct {
	Dim           int32
	CX            int32
	CZ            int32
	Subchunks     int
	BlockEntities map[PosI]BlockEntity
}

// World is a view of one downstream server's world.
type World struct {
	Key        string
	Name       string
	Seed       int64
	Spawn      PosI
	Gamemode   int32
	Difficulty int32
	Time       int32
	Raining    bool
	Thunder    bool
	Gamerules  map[string]string
}
