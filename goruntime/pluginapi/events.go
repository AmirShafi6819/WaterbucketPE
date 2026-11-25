package pluginapi

// Event is the marker interface every event implements. Plugins receive one
// Dispatch call per event (in load order) and return true to cancel it.
type Event interface {
	EventName() string
}

// ---- world events ----

// WorldInitEvent fires when a downstream server's world becomes visible
// (StartGame snapshot received): name, seed, spawn, gamemode, difficulty.
type WorldInitEvent struct {
	ServerKey  string
	WorldName  string
	Seed       int64
	Spawn      PosI
	Gamemode   int32
	Difficulty int32
}

func (e WorldInitEvent) EventName() string { return "WorldInitEvent" }

// WorldUnloadEvent fires when a downstream session ends and its world is
// dropped from the model.
type WorldUnloadEvent struct {
	ServerKey string
}

func (e WorldUnloadEvent) EventName() string { return "WorldUnloadEvent" }

// ChunkLoadEvent fires the first time a chunk becomes visible in the model.
type ChunkLoadEvent struct {
	ServerKey string
	Dim       int32
	CX, CZ    int32
}

func (e ChunkLoadEvent) EventName() string { return "ChunkLoadEvent" }

// ChunkUnloadEvent fires when the retention janitor evicts a chunk.
type ChunkUnloadEvent struct {
	ServerKey string
	Dim       int32
	CX, CZ    int32
}

func (e ChunkUnloadEvent) EventName() string { return "ChunkUnloadEvent" }

// ChunkSendEvent fires when the proxy relays a chunk to a player.
type ChunkSendEvent struct {
	ServerKey  string
	Dim        int32
	CX, CZ     int32
	PlayerName string
}

func (e ChunkSendEvent) EventName() string { return "ChunkSendEvent" }

// TimeChangeEvent fires on SetTime.
type TimeChangeEvent struct {
	ServerKey string
	Time      int32
}

func (e TimeChangeEvent) EventName() string { return "TimeChangeEvent" }

// WeatherChangeEvent fires on rain/thunder transitions.
type WeatherChangeEvent struct {
	ServerKey string
	Dim       int32
	Raining   bool
	Thunder   bool
}

func (e WeatherChangeEvent) EventName() string { return "WeatherChangeEvent" }

// DifficultyChangeEvent fires on SetDifficulty.
type DifficultyChangeEvent struct {
	ServerKey  string
	Difficulty int32
}

func (e DifficultyChangeEvent) EventName() string { return "DifficultyChangeEvent" }

// GameRuleChangeEvent fires on GameRulesChanged (one event per rule).
type GameRuleChangeEvent struct {
	ServerKey string
	Name      string
	Value     string
}

func (e GameRuleChangeEvent) EventName() string { return "GameRuleChangeEvent" }

// SpawnPositionChangeEvent fires on SetSpawnPosition.
type SpawnPositionChangeEvent struct {
	ServerKey string
	Dim       int32
	Pos       PosI
}

func (e SpawnPositionChangeEvent) EventName() string { return "SpawnPositionChangeEvent" }

// ---- block events ----

// BlockBreakEvent fires when a player finishes breaking a block. Cancelling
// drops the break action before the downstream server sees it.
type BlockBreakEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
	Item      Item
	Face      int32
}

func (e BlockBreakEvent) EventName() string { return "BlockBreakEvent" }

// BlockPlaceEvent fires when a player uses an item on a block face (placement
// or interaction attempt). Cancelling drops the action upstream.
type BlockPlaceEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
	Item      Item
	Face      int32
}

func (e BlockPlaceEvent) EventName() string { return "BlockPlaceEvent" }

// BlockStartBreakEvent fires when a player starts breaking a block.
type BlockStartBreakEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
	Item      Item
	Face      int32
}

func (e BlockStartBreakEvent) EventName() string { return "BlockStartBreakEvent" }

// BlockUpdateEvent fires for every block change the server sends to any
// client. OldName resolves from the live model when the chunk is loaded.
type BlockUpdateEvent struct {
	ServerKey string
	Dim       int32
	Pos       PosI
	RuntimeID int32
	BlockName string
	OldName   string
	Layer     int32
}

func (e BlockUpdateEvent) EventName() string { return "BlockUpdateEvent" }

// SignChangeEvent fires when a player edits a sign; cancelling drops the edit
// so the sign keeps its old text.
type SignChangeEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
	Lines     [4]string
}

func (e SignChangeEvent) EventName() string { return "SignChangeEvent" }

// ---- entity events ----

// EntitySpawnEvent fires when any entity becomes visible to a client.
type EntitySpawnEvent struct {
	ServerKey string
	Dim       int32
	Entity    Entity
}

func (e EntitySpawnEvent) EventName() string { return "EntitySpawnEvent" }

// EntityDespawnEvent fires when an entity is removed.
type EntityDespawnEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Type      string
}

func (e EntityDespawnEvent) EventName() string { return "EntityDespawnEvent" }

// EntityMoveEvent fires for every movement snapshot of any entity.
type EntityMoveEvent struct {
	ServerKey string
	Dim       int32
	Entity    Entity
	OnGround  bool
}

func (e EntityMoveEvent) EventName() string { return "EntityMoveEvent" }

// EntityMotionEvent fires when an entity's motion updates.
type EntityMotionEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Motion    Vec3
}

func (e EntityMotionEvent) EventName() string { return "EntityMotionEvent" }

// EntityEffectEvent fires on every mob effect add/modify/remove.
type EntityEffectEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Action    int32 // 1 add, 2 modify, 3 remove
	EffectID  int32
	Amplifier int32
	Duration  int32
}

func (e EntityEffectEvent) EventName() string { return "EntityEffectEvent" }

// Effect action values.
const (
	EffectAdd    = 1
	EffectModify = 2
	EffectRemove = 3
)

// EntityHealthChangeEvent fires on every health attribute update.
type EntityHealthChangeEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Old       float32
	New       float32
}

func (e EntityHealthChangeEvent) EventName() string { return "EntityHealthChangeEvent" }

// EntityDamageEvent fires when a health attribute drops: the proxy derived a
// real damage of (old - new). Observation only - the damage already happened
// server-side.
type EntityDamageEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Amount    float32
	Old       float32
	New       float32
}

func (e EntityDamageEvent) EventName() string { return "EntityDamageEvent" }

// EntityRegainHealthEvent fires when a health attribute rises.
type EntityRegainHealthEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Amount    float32
	Old       float32
	New       float32
}

func (e EntityRegainHealthEvent) EventName() string { return "EntityRegainHealthEvent" }

// EntityDataUpdateEvent fires on metadata flag updates.
type EntityDataUpdateEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Flags     uint64
}

func (e EntityDataUpdateEvent) EventName() string { return "EntityDataUpdateEvent" }

// EntityTeleportEvent fires for teleported movement snapshots.
type EntityTeleportEvent struct {
	ServerKey string
	Dim       int32
	Eid       int64
	Pos       Vec3
}

func (e EntityTeleportEvent) EventName() string { return "EntityTeleportEvent" }

// ---- player events ----

// PlayerInteractEvent fires on block interaction attempts (left click = break
// start, right click = use item on block). Cancelling drops the action.
type PlayerInteractEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
	Face      int32
	Item      Item
	Action    int32 // 0 left, 1 right
}

func (e PlayerInteractEvent) EventName() string { return "PlayerInteractEvent" }

// Interact action values.
const (
	LeftClickBlock  = 0
	RightClickBlock = 1
)

// PlayerItemHeldEvent fires when a player switches the held hotbar slot.
type PlayerItemHeldEvent struct {
	ServerKey     string
	Dim           int32
	Player        *Player
	Item          Item
	HotbarSlot    int32
	InventorySlot int32
}

func (e PlayerItemHeldEvent) EventName() string { return "PlayerItemHeldEvent" }

// PlayerDropItemEvent fires when a player drops an item; cancelling drops the
// packet so the item never leaves the inventory.
type PlayerDropItemEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Item      Item
	Count     int32
}

func (e PlayerDropItemEvent) EventName() string { return "PlayerDropItemEvent" }

// PlayerToggleSneakEvent fires when sneak state changes.
type PlayerToggleSneakEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	Sneaking  bool
}

func (e PlayerToggleSneakEvent) EventName() string { return "PlayerToggleSneakEvent" }

// PlayerToggleSprintEvent fires when sprint state changes.
type PlayerToggleSprintEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	Sprinting bool
}

func (e PlayerToggleSprintEvent) EventName() string { return "PlayerToggleSprintEvent" }

// PlayerAnimationEvent fires on animations; cancelling stops the swing from
// reaching the server (and therefore the other viewers).
type PlayerAnimationEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Action    int32
}

func (e PlayerAnimationEvent) EventName() string { return "PlayerAnimationEvent" }

// PlayerDeathEvent fires when a player's health attribute reaches zero.
type PlayerDeathEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
}

func (e PlayerDeathEvent) EventName() string { return "PlayerDeathEvent" }

// PlayerRespawnEvent fires when the server confirms a respawn.
type PlayerRespawnEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       Vec3
}

func (e PlayerRespawnEvent) EventName() string { return "PlayerRespawnEvent" }

// PlayerGameModeChangeEvent fires on SetPlayerGameType.
type PlayerGameModeChangeEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	Gamemode  int32
}

func (e PlayerGameModeChangeEvent) EventName() string { return "PlayerGameModeChangeEvent" }

// PlayerChangeDimensionEvent fires on dimension changes.
type PlayerChangeDimensionEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	FromDim   int32
	ToDim     int32
}

func (e PlayerChangeDimensionEvent) EventName() string { return "PlayerChangeDimensionEvent" }

// PlayerFormRespondEvent fires when a player answers a form. Cancelling
// drops the response, the server never learns it was answered.
type PlayerFormRespondEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	FormID    int32
	FormData  string
}

func (e PlayerFormRespondEvent) EventName() string { return "PlayerFormRespondEvent" }

// PlayerInteractEntityEvent fires when a player interacts with an entity.
type PlayerInteractEntityEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Target    *Entity
}

func (e PlayerInteractEntityEvent) EventName() string { return "PlayerInteractEntityEvent" }

// PlayerMouseOverEntityEvent fires when the player hovers an entity.
type PlayerMouseOverEntityEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	Target    *Entity
}

func (e PlayerMouseOverEntityEvent) EventName() string { return "PlayerMouseOverEntityEvent" }

// PlayerBedEnterEvent fires when a player tries to sleep; cancelling drops it.
type PlayerBedEnterEvent struct {
	Cancelled bool
	ServerKey string
	Dim       int32
	Player    *Player
	Pos       PosI
}

func (e PlayerBedEnterEvent) EventName() string { return "PlayerBedEnterEvent" }

// PlayerBedLeaveEvent fires when a player stops sleeping.
type PlayerBedLeaveEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
}

func (e PlayerBedLeaveEvent) EventName() string { return "PlayerBedLeaveEvent" }

// ---- inventory events ----

// InventoryOpenEvent fires when a window opens for a player.
type InventoryOpenEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	WindowID  int32
	TypeName  string
	Pos       PosI
}

func (e InventoryOpenEvent) EventName() string { return "InventoryOpenEvent" }

// InventoryCloseEvent fires when a window closes.
type InventoryCloseEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	WindowID  int32
}

func (e InventoryCloseEvent) EventName() string { return "InventoryCloseEvent" }

// InventoryUpdateEvent fires on every observed inventory slot change.
type InventoryUpdateEvent struct {
	ServerKey string
	Dim       int32
	Player    *Player
	WindowID  int32
	Slot      int32
	Item      Item
}

func (e InventoryUpdateEvent) EventName() string { return "InventoryUpdateEvent" }

// CraftItemEvent fires on crafting requests; cancelling sends a proper
// rejection back to the client through the stack request protocol.
type CraftItemEvent struct {
	Cancelled   bool
	ServerKey   string
	Dim         int32
	Player      *Player
	RecipeNetID int32
	Actions     string
}

func (e CraftItemEvent) EventName() string { return "CraftItemEvent" }

// InventoryTransactionEvent fires for generic inventory transactions.
type InventoryTransactionEvent struct {
	Cancelled  bool
	ServerKey  string
	Dim        int32
	Player     *Player
	TxnType    int32
	ActionType int32
}

func (e InventoryTransactionEvent) EventName() string { return "InventoryTransactionEvent" }
