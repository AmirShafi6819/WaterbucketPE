// Package bridge implements the binary transport between the Java proxy core
// and the Go runtime sidecar. The wire format is intentionally compact: a
// 4-byte little-endian frame length, a single opcode byte, then typed fields.
// Both this package and the Java side (dev.waterbucket.waterbucketpe.network.goruntime)
// are kept byte-compatible; the conformance tests on each side pin the format
// with golden vectors.
package bridge

import "fmt"

// ProtocolVersion guards the handshake. Java refuses sidecars speaking another
// version and vice versa, so an old runtime never misparses a new stream.
const ProtocolVersion = 1

// MaxFrameBytes bounds a single frame. The largest legitimate payload is a
// full chunk blob (~1-2 MB); 32 MB leaves generous headroom while still
// rejecting garbage lengths immediately.
const MaxFrameBytes = 32 << 20

// Opcodes.
const (
	OpHello        = 0x01 // Java -> Go, first frame after connect
	OpHelloAck     = 0x02 // Go -> Java
	OpPing         = 0x0F // either side
	OpPong         = 0x10 // either side
	OpDelta        = 0x20 // Java -> Go, world observation records
	OpEventSync    = 0x30 // Java -> Go, cancellable event, wants a verdict
	OpEventResult  = 0x31 // Go -> Java, verdict for OpEventSync
	OpEventAsync   = 0x32 // Java -> Go, observation event, no verdict needed
	OpQuery        = 0x40 // Java -> Go, world model read
	OpQueryResult  = 0x41 // Go -> Java
	OpRpcCall      = 0x50 // Go -> Java, proxy-side action
	OpRpcResult    = 0x51 // Java -> Go
	OpLog          = 0x60 // Go -> Java, sidecar log lines forwarded to the proxy log
	OpPluginStatus = 0x70 // Go -> Java, plugin list after every load/unload
	OpShutdown     = 0x7F // either side, graceful exit
)

// QueryStatus values carried in OpQueryResult.
const (
	QueryOK          = 0
	QueryNotFound    = 1
	QueryUnavailable = 2 // runtime disabled, world unknown, etc.
)

// Plugin states carried in OpPluginStatus.
const (
	PluginDisabled = 0
	PluginEnabled  = 1
	PluginFailed   = 2
)

// Delta types: world observations streamed from the proxy into the Go model.
const (
	DeltaWorldMeta        = 1  // StartGamePacket snapshot
	DeltaChunk            = 2  // raw serialized chunk blob (subchunks inside)
	DeltaBlockUpdate      = 3  // UpdateBlock(Psync)Packet
	DeltaBlockEntity      = 4  // BlockEntityDataPacket (server -> client)
	DeltaEntityAdd        = 5  // AddEntity/AddPlayer/AddItemEntity
	DeltaEntityRemove     = 6  // RemoveEntity
	DeltaEntityMove       = 7  // MoveEntityAbsolute/Delta/MovePlayer
	DeltaEntityMotion     = 8  // SetActorMotion
	DeltaEntityMeta       = 9  // SetEntityData (flags bitmask)
	DeltaEntityAttribute  = 10 // UpdateAttributes
	DeltaEntityEffect     = 11 // MobEffect
	DeltaPlayerInfo       = 12 // player identity snapshot (login/rewrite)
	DeltaPlayerRemove     = 13 // player left
	DeltaPlayerEquip      = 14 // MobEquipment
	DeltaContainer        = 15 // ContainerOpen/Close
	DeltaInventoryContent = 16 // InventoryContentPacket
	DeltaInventorySlot    = 17 // InventorySlotPacket
	DeltaWorldTime        = 18 // SetTimePacket
	DeltaWorldDifficulty  = 19 // SetDifficultyPacket
	DeltaWorldGameRule    = 20 // GameRulesChangedPacket (single rule)
	DeltaWeather          = 21 // LevelEventPacket weather ids
	DeltaSpawnPos         = 22 // SetSpawnPositionPacket
	DeltaServerDown       = 23 // downstream session ended
	DeltaPlayerState      = 24 // PlayerAuthInput position/rotation snapshot
)

// Event ids. Layout of each event's fields is positional and shared with the
// Java side; the field order is documented right above every constant and both
// sides have conformance tests pinning it.
const (
	EventWorldInit             = 1  // [0]serverKey str [1]worldName str [2]seed i64 [3]spawn vec3i [4]gamemode i32 [5]difficulty i32
	EventWorldUnload           = 2  // [0]serverKey str
	EventChunkLoad             = 3  // [0]serverKey str [1]dim i32 [2]cx i32 [3]cz i32
	EventChunkUnload           = 4  // same as ChunkLoad
	EventChunkSend             = 5  // [0]serverKey [1]dim [2]cx [3]cz [4]playerName str
	EventTimeChange            = 6  // [0]serverKey [1]time i32
	EventWeatherChange         = 7  // [0]serverKey [1]dim i32 [2]raining bool [3]thunder bool
	EventDifficultyChange      = 8  // [0]serverKey [1]difficulty i32
	EventGameRuleChange        = 9  // [0]serverKey [1]name str [2]value str
	EventSpawnPositionChange   = 10 // [0]serverKey [1]dim i32 [2]pos vec3i
	EventBlockBreak            = 20 // CANCELABLE. [0]serverKey [1]dim [2]playerName str [3]pos vec3i [4]item item [5]face i32
	EventBlockPlace            = 21 // CANCELABLE. same fields as BlockBreak
	EventBlockStartBreak       = 24 // CANCELABLE. same fields as BlockBreak
	EventBlockUpdate           = 22 // [0]serverKey [1]dim [2]pos vec3i [3]runtimeId i32 [4]blockName str [5]layer i32
	EventSignChange            = 23 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]pos vec3i [4]lines bytes(4 lines, NUL separated)
	EventEntitySpawn           = 30 // [0]serverKey [1]dim [2]eid i64 [3]type str [4]pos vec3f [5]isPlayer bool [6]name str
	EventEntityDespawn         = 31 // [0]serverKey [1]dim [2]eid i64 [3]type str
	EventEntityMove            = 32 // [0]serverKey [1]dim [2]eid [3]pos vec3f [4]yaw f32 [5]pitch f32 [6]onGround bool
	EventEntityMotion          = 33 // [0]serverKey [1]dim [2]eid [3]motion vec3f
	EventEntityEffect          = 34 // [0]serverKey [1]dim [2]eid [3]event u8 [4]effectId i32 [5]amplifier i32 [6]duration i32
	EventEntityHealthChange    = 35 // [0]serverKey [1]dim [2]eid [3]old f32 [4]new f32
	EventEntityDamage          = 36 // [0]serverKey [1]dim [2]eid [3]cause str [4]amount f32 [5]old f32 [6]new f32
	EventEntityRegainHealth    = 37 // [0]serverKey [1]dim [2]eid [3]amount f32 [4]old f32 [5]new f32
	EventEntityDataUpdate      = 38 // [0]serverKey [1]dim [2]eid [3]flags i64
	EventEntityTeleport        = 39 // [0]serverKey [1]dim [2]eid [3]pos vec3f
	EventPlayerInteract        = 50 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]pos vec3i [4]face i32 [5]item item [6]action i32 (0=left,1=right)
	EventPlayerItemHeld        = 51 // [0]serverKey [1]dim [2]playerName [3]item item [4]hotbarSlot i32 [5]inventorySlot i32
	EventPlayerDropItem        = 52 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]item item [4]count i32
	EventPlayerToggleSneak     = 53 // [0]serverKey [1]dim [2]playerName [3]sneaking bool
	EventPlayerToggleSprint    = 54 // [0]serverKey [1]dim [2]playerName [3]sprinting bool
	EventPlayerAnimation       = 55 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]action i32
	EventPlayerDeath           = 56 // [0]serverKey [1]dim [2]playerName [3]eid i64
	EventPlayerRespawn         = 57 // [0]serverKey [1]dim [2]playerName [3]pos vec3f
	EventPlayerGameModeChange  = 58 // [0]serverKey [1]dim [2]playerName [3]gamemode i32
	EventPlayerChangeDimension = 59 // [0]serverKey [1]dim [2]playerName [3]fromDim i32 [4]toDim i32
	EventPlayerFormRespond     = 60 // [0]serverKey [1]dim [2]playerName [3]formId i32 [4]formData str
	EventPlayerInteractEntity  = 61 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]targetEid i64 [4]targetType str
	EventPlayerMouseOver       = 62 // [0]serverKey [1]dim [2]playerName [3]targetEid i64 [4]targetType str
	EventPlayerBedEnter        = 63 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]pos vec3i
	EventPlayerBedLeave        = 64 // [0]serverKey [1]dim [2]playerName
	EventInventoryOpen         = 70 // [0]serverKey [1]dim [2]playerName [3]windowId i32 [4]typeName str [5]pos vec3i
	EventInventoryClose        = 71 // [0]serverKey [1]dim [2]playerName [3]windowId i32
	EventInventoryUpdate       = 72 // [0]serverKey [1]dim [2]playerName [3]windowId i32 [4]slot i32 [5]item item
	EventCraftItem             = 73 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]recipeNetId i32 [4]actions str
	EventInventoryTransaction  = 74 // CANCELABLE. [0]serverKey [1]dim [2]playerName [3]txnType i32 [4]actionType i32
	EventCount                 = 75
)

// PlayerInteractEvent action values (mirrors the original software's semantic).
const (
	ActionLeftClickBlock  = 0
	ActionRightClickBlock = 1
)

// Item is the wire form of an inventory item shared by deltas and events.
type Item struct {
	Identifier string
	Damage     int32
	Count      int32
	NetID      int64
	HasNBT     bool
	NBT        []byte
}

// String renders items compactly for logs and events.
func (i Item) String() string {
	if i.Count == 0 && i.Identifier == "" {
		return "Air"
	}
	return fmt.Sprintf("%s x%d", i.Identifier, i.Count)
}

// IsAir reports whether the item carries no data.
func (i Item) IsAir() bool {
	return i.Identifier == "" && i.Count == 0
}
