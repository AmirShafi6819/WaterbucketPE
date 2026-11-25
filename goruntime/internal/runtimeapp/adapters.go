package runtimeapp

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/world"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

// worldAccess adapts the world registry into pluginapi.WorldAccess: every
// plugin query lands here and reads the live model directly (no RPC hop -
// plugins run inside the runtime process).
type worldAccess struct {
	// staleReg is the registry captured at construction; after the handshake
	// the app rebuilds its registry with the negotiated bounds, so every read
	// resolves through the app to always see the live model.
	staleReg *world.Registry
	app      *App
}

// reg returns the app's live registry.
func (w *worldAccess) reg() *world.Registry {
	if w.app != nil && w.app.registry != nil {
		return w.app.registry
	}
	return w.staleReg
}

func (w *worldAccess) ListWorlds() []pluginapi.World {
	summaries := w.reg().WorldList()
	out := make([]pluginapi.World, 0, len(summaries))
	for _, s := range summaries {
		out = append(out, w.toWorld(s))
	}
	return out
}

func (w *worldAccess) toWorld(s world.WorldSummary) pluginapi.World {
	worldRef := w.reg().World(s.Key)
	out := pluginapi.World{
		Key:        s.Key,
		Name:       s.Name,
		Seed:       s.Seed,
		Spawn:      pluginapi.PosI{X: s.Spawn.X, Y: s.Spawn.Y, Z: s.Spawn.Z},
		Gamemode:   s.Gamemode,
		Difficulty: s.Difficulty,
		Gamerules:  map[string]string{},
	}
	if worldRef != nil {
		out.Time = worldRef.Time
		out.Raining = worldRef.Raining
		out.Thunder = worldRef.Thunder
		for k, v := range worldRef.Gamerules {
			out.Gamerules[k] = v
		}
	}
	return out
}

func (w *worldAccess) GetWorld(key string) *pluginapi.World {
	for _, s := range w.reg().WorldList() {
		if s.Key == key {
			out := w.toWorld(s)
			return &out
		}
	}
	return nil
}

func (w *worldAccess) GetBlock(serverKey string, dim int32, x, y, z int32) pluginapi.Block {
	entry, loaded := w.reg().BlockQuery(serverKey, dim, x, y, z)
	out := pluginapi.Block{
		Name:       entry.Name,
		RuntimeID:  -1,
		Position:   pluginapi.PosI{X: x, Y: y, Z: z},
		Properties: entry.States,
	}
	if !loaded {
		out.Name = "minecraft:unloaded"
	}
	return out
}

func (w *worldAccess) IsChunkLoaded(serverKey string, dim, cx, cz int32) bool {
	return w.reg().ChunkLoaded(serverKey, dim, cx, cz)
}

func (w *worldAccess) GetChunk(serverKey string, dim, cx, cz int32) (*pluginapi.Chunk, bool) {
	subs, entityCount, ok := w.reg().ChunkStats(serverKey, dim, cx, cz)
	if !ok {
		return nil, false
	}
	// Collect block entities by scanning the chunk's stored entities.
	chunk := &pluginapi.Chunk{
		Dim: dim, CX: cx, CZ: cz,
		Subchunks:     subs,
		BlockEntities: map[pluginapi.PosI]pluginapi.BlockEntity{},
	}
	w.reg().ForEachBlockEntity(serverKey, dim, cx, cz, func(pos world.BlockPos, be *world.BlockEntityData) {
		chunk.BlockEntities[pluginapi.PosI{X: pos.X, Y: pos.Y, Z: pos.Z}] = pluginapi.BlockEntity{
			Type: be.Type, Position: pluginapi.PosI{X: pos.X, Y: pos.Y, Z: pos.Z}, NBT: be.NBT,
		}
	})
	_ = entityCount
	return chunk, true
}

func (w *worldAccess) GetBlockEntity(serverKey string, dim int32, x, y, z int32) (*pluginapi.BlockEntity, bool) {
	be, ok := w.reg().BlockEntityQuery(serverKey, dim, x, y, z)
	if !ok || be == nil {
		return nil, false
	}
	return &pluginapi.BlockEntity{
		Type:     be.Type,
		Position: pluginapi.PosI{X: x, Y: y, Z: z},
		NBT:      be.NBT,
	}, true
}

func (w *worldAccess) GetEntity(serverKey string, eid int64) (*pluginapi.Entity, bool) {
	e, ok := w.reg().EntityQuery(serverKey, eid)
	if !ok {
		return nil, false
	}
	return w.app.toAPIEntity(e), true
}

func (w *worldAccess) GetEntitiesNear(serverKey string, dim int32, x, y, z, radius float32, limit int) []*pluginapi.Entity {
	entities := w.reg().EntitiesNear(serverKey, dim, x, y, z, radius, limit)
	out := make([]*pluginapi.Entity, 0, len(entities))
	for _, e := range entities {
		out = append(out, w.app.toAPIEntity(e))
	}
	return out
}

func (w *worldAccess) GetPlayer(serverKey string, eid int64) (*pluginapi.Player, bool) {
	p, ok := w.reg().PlayerQuery(serverKey, eid)
	if !ok {
		return nil, false
	}
	return w.app.toAPIPlayer(serverKey, p), true
}

func (w *worldAccess) GetPlayerByName(serverKey, name string) (*pluginapi.Player, bool) {
	p, ok := w.reg().PlayerByName(serverKey, name)
	if !ok {
		return nil, false
	}
	return w.app.toAPIPlayer(serverKey, p), true
}

func (w *worldAccess) GetInventory(serverKey string, eid int64, windowID int32) ([]pluginapi.Item, bool) {
	items, ok := w.reg().InventoryQuery(serverKey, eid, windowID)
	if !ok {
		return nil, false
	}
	out := make([]pluginapi.Item, len(items))
	for i, item := range items {
		out[i] = toAPIItem(item)
	}
	return out, true
}

// proxyAccess implements pluginapi.ProxyAccess over the bridge RPC: every
// call is a real operation on the Java proxy core.
type proxyAccess struct {
	app *App
}

func (p *proxyAccess) call(obj, method string, fields []bridge.Field) error {
	_, err := p.app.Call(obj, method, fields, 5*time.Second)
	return err
}

func playerName(name string) bridge.Field {
	return bridge.Field{Type: bridge.TypeStr, Str: name}
}

func (p *proxyAccess) SendMessage(playerNameArg, message string) error {
	return p.call("player", "sendMessage", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: message}})
}

func (p *proxyAccess) SendTip(playerNameArg, message string) error {
	return p.call("player", "sendTip", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: message}})
}

func (p *proxyAccess) SendPopup(playerNameArg, message, subtitle string) error {
	return p.call("player", "sendPopup", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: message}, {Type: bridge.TypeStr, Str: subtitle}})
}

func (p *proxyAccess) SendTitle(playerNameArg, title, subtitle string) error {
	return p.call("player", "sendTitle", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: title}, {Type: bridge.TypeStr, Str: subtitle}})
}

func (p *proxyAccess) Kick(playerNameArg, reason string) error {
	return p.call("player", "kick", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: reason}})
}

func (p *proxyAccess) Transfer(playerNameArg, serverName string) error {
	return p.call("player", "transfer", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: serverName}})
}

func (p *proxyAccess) BroadcastMessage(message string) error {
	return p.call("server", "broadcastMessage", []bridge.Field{{Type: bridge.TypeStr, Str: message}})
}

func (p *proxyAccess) DispatchCommand(commandLine string) error {
	return p.call("server", "dispatchCommand", []bridge.Field{{Type: bridge.TypeStr, Str: commandLine}})
}

func (p *proxyAccess) DispatchCommandAs(playerNameArg, commandLine string) error {
	return p.call("server", "dispatchCommandAs", []bridge.Field{playerName(playerNameArg), {Type: bridge.TypeStr, Str: commandLine}})
}

func (p *proxyAccess) GetOnlinePlayers() []string {
	results, err := p.app.Call("server", "getOnlinePlayers", nil, 5*time.Second)
	if err != nil || len(results) == 0 {
		return []string{}
	}
	var names []string
	if err := json.Unmarshal([]byte(results[0].Str), &names); err != nil {
		return []string{}
	}
	return names
}

func (p *proxyAccess) OnlineCount() int {
	results, err := p.app.Call("server", "getOnlineCount", nil, 5*time.Second)
	if err != nil || len(results) == 0 {
		return 0
	}
	if results[0].Type == bridge.TypeI32 {
		return int(results[0].I32)
	}
	return 0
}

func (p *proxyAccess) ProxyVersion() string {
	results, err := p.app.Call("server", "getProxyVersion", nil, 5*time.Second)
	if err != nil || len(results) == 0 {
		return "unknown"
	}
	return results[0].Str
}

// toAPIEntity converts a model entity snapshot.
func (a *App) toAPIEntity(e *world.Entity) *pluginapi.Entity {
	return &pluginapi.Entity{
		Eid:      e.Eid,
		Type:     e.Type,
		Dim:      e.Dim,
		X:        e.Pos[0],
		Y:        e.Pos[1],
		Z:        e.Pos[2],
		MotX:     e.Motion[0],
		MotY:     e.Motion[1],
		MotZ:     e.Motion[2],
		Yaw:      e.Yaw,
		Pitch:    e.Pitch,
		Flags:    e.Flags,
		Attrs:    e.Attrs,
		IsPlayer: e.IsPlayer,
		Name:     e.Name,
		Xuid:     e.Xuid,
	}
}

var _ pluginapi.WorldAccess = (*worldAccess)(nil)
var _ pluginapi.ProxyAccess = (*proxyAccess)(nil)
var _ = fmt.Sprintf
