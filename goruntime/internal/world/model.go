// Package world holds the Go runtime's live model of the worlds every
// downstream server streams through the proxy: chunks with real block data,
// entities, block entities, players and inventories. Everything here is fed
// exclusively by bridge deltas, so it is always exactly what the clients were
// shown - no guessing, no stale snapshots from disk.
package world

import (
	"sort"
	"sync"
	"time"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
)

// ChunkKey addresses one chunk inside one dimension.
type ChunkKey struct {
	Dim int32
	CX  int32
	CZ  int32
}

// BlockPos is an integer world position.
type BlockPos struct{ X, Y, Z int32 }

// Dimension is the chunk/entity space of one world dimension.
type Dimension struct {
	Chunks   map[ChunkKey]*Chunk
	Entities map[int64]*Entity
	Players  map[int64]*Player
}

// World is one downstream server's world (all its dimensions).
type World struct {
	Key        string
	Name       string
	Seed       int64
	Spawn      BlockPos
	Gamemode   int32
	Difficulty int32
	Gamerules  map[string]string
	Dimensions map[int32]*Dimension
	BlockTable map[int32]PaletteEntry
	Raining    bool
	Thunder    bool
	Time       int32
	LastActive int64
}

// Chunk stores decoded subchunks plus the block entities observed on it.
type Chunk struct {
	Key            ChunkKey
	Subs           map[uint8]*Subchunk
	BlockEntityMap map[BlockPos]*BlockEntityData
	LastAccess     int64
}

// BlockEntityData is a parsed block entity (sign text, chest inventory NBT...).
type BlockEntityData struct {
	Pos  BlockPos
	Type string
	NBT  map[string]interface{}
}

// Entity is every tracked entity: mobs, items, projectiles, players.
type Entity struct {
	Eid      int64
	Type     string
	Dim      int32
	Pos      [3]float32
	Motion   [3]float32
	Yaw      float32
	Pitch    float32
	Flags    uint64
	IsPlayer bool
	Name     string
	Xuid     string
	Attrs    map[string]float32
	LastSeen int64
}

// Player is a tracked player on a downstream server.
type Player struct {
	Entity
	Gamemode    int32
	HeldItem    bridge.Item
	Inventories map[int32][]bridge.Item
}

// Registry owns every world and serializes access. A single RWMutex keeps the
// implementation honest and still fast: deltas apply in bursts on one writer,
// queries are short read locks.
type Registry struct {
	mu             sync.RWMutex
	worlds         map[string]*World
	retention      time.Duration
	maxChunks      int
	dropWorldAfter time.Duration
	// OnChunkLoad fires the first time a chunk becomes resident.
	OnChunkLoad func(serverKey string, dim int32, cx, cz int32)
	// OnChunkUnload fires when the janitor evicts a chunk.
	OnChunkUnload func(serverKey string, dim int32, cx, cz int32)
}

// NewRegistry creates a registry with the given retention bounds.
func NewRegistry(retentionSec, maxChunks int) *Registry {
	if retentionSec <= 0 {
		retentionSec = 300
	}
	if maxChunks <= 0 {
		maxChunks = 4096
	}
	return &Registry{
		worlds:         make(map[string]*World),
		retention:      time.Duration(retentionSec) * time.Second,
		maxChunks:      maxChunks,
		dropWorldAfter: time.Duration(retentionSec*2) * time.Second,
	}
}

func nowNano() int64 {
	return time.Now().UnixNano()
}

// dimension returns (creating) the dimension map for a world.
func (w *World) dimension(dim int32) *Dimension {
	d, ok := w.Dimensions[dim]
	if !ok {
		d = &Dimension{
			Chunks:   make(map[ChunkKey]*Chunk),
			Entities: make(map[int64]*Entity),
			Players:  make(map[int64]*Player),
		}
		w.Dimensions[dim] = d
	}
	return d
}

// World returns the world or nil.
func (r *Registry) World(key string) *World {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.worlds[key]
}

// WorldCount reports how many live worlds are tracked.
func (r *Registry) WorldCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.worlds)
}

// ensureWorld returns (creating) the world under a server key.
func (r *Registry) ensureWorld(key string) *World {
	w, ok := r.worlds[key]
	if !ok {
		w = &World{
			Key:        key,
			Gamerules:  make(map[string]string),
			Dimensions: make(map[int32]*Dimension),
		}
		r.worlds[key] = w
	}
	w.LastActive = nowNano()
	return w
}

// ApplyWorldMeta ingests a WORLD_META delta (StartGame snapshot).
func (r *Registry) ApplyWorldMeta(key, name string, seed int64, spawn BlockPos, gamemode, difficulty int32, gamerules map[string]string, blockTable map[int32]PaletteEntry) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.ensureWorld(key)
	w.Name = name
	w.Seed = seed
	w.Spawn = spawn
	w.Gamemode = gamemode
	w.Difficulty = difficulty
	if gamerules != nil {
		w.Gamerules = gamerules
	}
	if blockTable != nil {
		w.BlockTable = blockTable
	}
}

// ApplyChunk ingests a serialized chunk blob and decodes it. Returns true
// when the chunk was newly created (the first observation of this chunk).
func (r *Registry) ApplyChunk(key string, dim int32, cx, cz int32, blob []byte) bool {
	subs, err := SplitBlob(blob)
	if err != nil || len(subs) == 0 {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.ensureWorld(key)
	d := w.dimension(dim)
	ck := ChunkKey{Dim: dim, CX: cx, CZ: cz}
	created := false
	chunk, ok := d.Chunks[ck]
	if !ok {
		chunk = &Chunk{
			Key:            ck,
			Subs:           make(map[uint8]*Subchunk, len(subs)),
			BlockEntityMap: make(map[BlockPos]*BlockEntityData, 4),
		}
		d.Chunks[ck] = chunk
		created = true
	}
	chunk.LastAccess = nowNano()
	for _, sub := range subs {
		chunk.Subs[sub.Index] = sub
	}
	if created && r.OnChunkLoad != nil {
		cb := r.OnChunkLoad
		r.mu.Unlock()
		cb(key, dim, cx, cz)
		r.mu.Lock()
	}
	return created
}

// ApplyBlockUpdate ingests one block change.
func (r *Registry) ApplyBlockUpdate(key string, dim int32, x, y, z int32, runtimeId int32) (string, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return "", false
	}
	w.LastActive = nowNano()
	// Resolve the runtime id against the world's block table when present.
	name := ""
	if w.BlockTable != nil {
		if entry, found := w.BlockTable[runtimeId]; found {
			name = entry.Name
		}
	}
	return name, true
}

// ApplyBlockEntity ingests a block entity payload.
func (r *Registry) ApplyBlockEntity(key string, dim int32, x, y, z int32, typeName string, nbtData map[string]interface{}) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	d := w.dimension(dim)
	cx, cz, _ := coordToChunk(x, y, z)
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk == nil {
		return
	}
	chunk.LastAccess = nowNano()
	chunk.BlockEntityMap[BlockPos{X: x, Y: y, Z: z}] = &BlockEntityData{
		Pos:  BlockPos{X: x, Y: y, Z: z},
		Type: typeName,
		NBT:  nbtData,
	}
}

// coordToChunk converts world coordinates to chunk and subchunk coordinates.
func coordToChunk(x, y, z int32) (cx, cz int32, sy uint8) {
	cx = x >> 4
	cz = z >> 4
	sy = uint8(y>>4 + 4) // y -64..319 -> 0..23, clamp negatives
	if y < -64 {
		sy = 0
	}
	if sy > 23 {
		sy = 23
	}
	return
}

// ApplyEntityAdd ingests an entity spawn.
func (r *Registry) ApplyEntityAdd(key string, dim int32, eid int64, entityType string, x, y, z, yaw, pitch float32, motion [3]float32, isPlayer bool, name, xuid string, flags uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.ensureWorld(key)
	d := w.dimension(dim)
	e := &Entity{
		Eid:      eid,
		Type:     entityType,
		Dim:      dim,
		Pos:      [3]float32{x, y, z},
		Motion:   motion,
		Yaw:      yaw,
		Pitch:    pitch,
		Flags:    flags,
		IsPlayer: isPlayer,
		Name:     name,
		Xuid:     xuid,
		Attrs:    make(map[string]float32, 4),
		LastSeen: nowNano(),
	}
	if existing := d.Entities[eid]; existing != nil {
		// Re-spawn of a known id: refresh in place.
		e.Attrs = existing.Attrs
	}
	d.Entities[eid] = e
	if isPlayer {
		p, found := d.Players[eid]
		if !found {
			p = &Player{Entity: *e, Inventories: make(map[int32][]bridge.Item, 4)}
			d.Players[eid] = p
		}
		p.Entity = *e
	}
}

// ApplyEntityRemove ingests an entity removal.
func (r *Registry) ApplyEntityRemove(key string, eid int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		delete(d.Entities, eid)
		if _, isPlayer := d.Players[eid]; isPlayer {
			delete(d.Players, eid)
		}
	}
}

// ApplyEntityMove ingests one movement snapshot.
func (r *Registry) ApplyEntityMove(key string, eid int64, x, y, z, yaw, pitch float32, onGround bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if e := d.Entities[eid]; e != nil {
			e.Pos = [3]float32{x, y, z}
			e.Yaw = yaw
			e.Pitch = pitch
			e.LastSeen = nowNano()
		}
		if p := d.Players[eid]; p != nil {
			p.Pos = [3]float32{x, y, z}
			p.Yaw = yaw
			p.Pitch = pitch
		}
	}
}

// ApplyEntityMotion ingests one motion snapshot.
func (r *Registry) ApplyEntityMotion(key string, eid int64, mx, my, mz float32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if e := d.Entities[eid]; e != nil {
			e.Motion = [3]float32{mx, my, mz}
		}
	}
}

// ApplyEntityMeta ingests a metadata flag bitmask.
func (r *Registry) ApplyEntityMeta(key string, eid int64, flags uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if e := d.Entities[eid]; e != nil {
			e.Flags = flags
		}
	}
}

// ApplyEntityAttribute ingests one attribute update.
func (r *Registry) ApplyEntityAttribute(key string, eid int64, attr string, value float32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if e := d.Entities[eid]; e != nil {
			if e.Attrs == nil {
				e.Attrs = make(map[string]float32, 4)
			}
			e.Attrs[attr] = value
		}
	}
}

// ApplyPlayerInfo ingests a player identity snapshot.
func (r *Registry) ApplyPlayerInfo(key string, eid int64, name, xuid string, gamemode, dim int32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.ensureWorld(key)
	d := w.dimension(dim)
	p, found := d.Players[eid]
	if !found {
		p = &Player{Inventories: make(map[int32][]bridge.Item, 4)}
		d.Players[eid] = p
		d.Entities[eid] = &p.Entity
	}
	p.Eid = eid
	p.Name = name
	p.Xuid = xuid
	p.Gamemode = gamemode
	p.Dim = dim
	p.IsPlayer = true
	p.LastSeen = nowNano()
	if d.Entities[eid] == nil {
		d.Entities[eid] = &p.Entity
	}
}

// ApplyPlayerRemove ingests a player leaving.
func (r *Registry) ApplyPlayerRemove(key string, eid int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		delete(d.Players, eid)
	}
}

// ApplyEquip ingests a held-item update.
func (r *Registry) ApplyEquip(key string, eid int64, item bridge.Item, hotbarSlot, inventorySlot int32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if p := d.Players[eid]; p != nil {
			p.HeldItem = item
			if p.Inventories == nil {
				p.Inventories = make(map[int32][]bridge.Item, 4)
			}
			// Window 0x2D? MobEquipment targets hotbar; keep the slot view small.
			inv := p.Inventories[windowHotbar]
			if inv == nil {
				inv = make([]bridge.Item, 9)
				p.Inventories[windowHotbar] = inv
			}
			if hotbarSlot >= 0 && hotbarSlot < int32(len(inv)) {
				inv[hotbarSlot] = item
			}
		}
	}
}

// Window id the proxy maps hotbar equipment into.
const windowHotbar = 12

// ApplyInventoryContent ingests a whole window snapshot.
func (r *Registry) ApplyInventoryContent(key string, eid int64, windowID int32, items map[int32]bridge.Item) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if p := d.Players[eid]; p != nil {
			if p.Inventories == nil {
				p.Inventories = make(map[int32][]bridge.Item, 4)
			}
			inv := p.Inventories[windowID]
			if inv == nil {
				inv = make([]bridge.Item, 0)
			}
			maxSlot := int32(-1)
			for slot := range items {
				if slot > maxSlot {
					maxSlot = slot
				}
			}
			if int(maxSlot) >= len(inv) {
				grown := make([]bridge.Item, maxSlot+1)
				copy(grown, inv)
				inv = grown
			}
			for slot, item := range items {
				if slot >= 0 && slot < int32(len(inv)) {
					inv[slot] = item
				}
			}
			p.Inventories[windowID] = inv
		}
	}
}

// ApplyInventorySlot ingests a single slot update.
func (r *Registry) ApplyInventorySlot(key string, eid int64, windowID, slot int32, item bridge.Item) {
	r.mu.Lock()
	defer r.mu.Unlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	for _, d := range w.Dimensions {
		if p := d.Players[eid]; p != nil {
			if p.Inventories == nil {
				p.Inventories = make(map[int32][]bridge.Item, 4)
			}
			inv := p.Inventories[windowID]
			if inv == nil {
				inv = make([]bridge.Item, slot+1)
				p.Inventories[windowID] = inv
			}
			if int(slot) >= len(inv) {
				grown := make([]bridge.Item, slot+1)
				copy(grown, inv)
				inv = grown
				p.Inventories[windowID] = inv
			}
			inv[slot] = item
		}
	}
}

// ApplyContainer records a window open/close (type + position snapshot).
func (r *Registry) ApplyContainer(key string, eid int64, windowID int32, open bool, typeName string, x, y, z int32) {
	// The model keeps inventories themselves; open/close only feeds events.
}

// ApplyTime / ApplyDifficulty / ApplyGameRule / ApplyWeather / ApplySpawnPos.
func (r *Registry) ApplyTime(key string, t int32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w, ok := r.worlds[key]; ok {
		w.Time = t
	}
}

func (r *Registry) ApplyDifficulty(key string, diff int32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w, ok := r.worlds[key]; ok {
		w.Difficulty = diff
	}
}

func (r *Registry) ApplyGameRule(key, name, value string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w, ok := r.worlds[key]; ok {
		w.Gamerules[name] = value
	}
}

func (r *Registry) ApplyWeather(key string, raining, thunder bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w, ok := r.worlds[key]; ok {
		w.Raining = raining
		w.Thunder = thunder
	}
}

func (r *Registry) ApplySpawnPos(key string, x, y, z int32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w, ok := r.worlds[key]; ok {
		w.Spawn = BlockPos{x, y, z}
	}
}

// ApplyServerDown drops a world whose downstream session ended.
func (r *Registry) ApplyServerDown(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.worlds, key)
}

// ---- queries (read paths used by plugins through the API and by Java) ----

// BlockQuery resolves one block; returns name, states and loaded flag.
func (r *Registry) BlockQuery(key string, dim int32, x, y, z int32) (PaletteEntry, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return PaletteEntry{Name: "minecraft:air"}, false
	}
	d := w.Dimensions[dim]
	if d == nil {
		return PaletteEntry{Name: "minecraft:air"}, false
	}
	cx, cz, sy := coordToChunk(x, y, z)
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk == nil {
		return PaletteEntry{Name: "minecraft:air"}, false
	}
	chunk.LastAccess = nowNano()
	sub := chunk.Subs[sy]
	if sub == nil {
		return PaletteEntry{Name: "minecraft:air"}, true
	}
	entry := chunk.BlockAt(d, sub, int(x&15), int(y&15), int(z&15), w.BlockTable)
	return entry, true
}

// ChunkLoaded reports whether a chunk is resident.
func (r *Registry) ChunkLoaded(key string, dim, cx, cz int32) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return false
	}
	d := w.Dimensions[dim]
	if d == nil {
		return false
	}
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk != nil {
		chunk.LastAccess = nowNano()
	}
	return chunk != nil
}

// ChunkStats returns the subchunk count and population of a chunk.
func (r *Registry) ChunkStats(key string, dim, cx, cz int32) (subs int, entities int, ok bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, found := r.worlds[key]
	if !found {
		return 0, 0, false
	}
	d := w.Dimensions[dim]
	if d == nil {
		return 0, 0, false
	}
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk == nil {
		return 0, 0, false
	}
	chunk.LastAccess = nowNano()
	return len(chunk.Subs), len(chunk.BlockEntityMap), true
}

// BlockEntityQuery returns one block entity.
func (r *Registry) BlockEntityQuery(key string, dim int32, x, y, z int32) (*BlockEntityData, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil, false
	}
	d := w.Dimensions[dim]
	if d == nil {
		return nil, false
	}
	cx, cz, _ := coordToChunk(x, y, z)
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk == nil {
		return nil, false
	}
	chunk.LastAccess = nowNano()
	be, ok := chunk.BlockEntityMap[BlockPos{X: x, Y: y, Z: z}]
	return be, ok
}

// EntityQuery returns one entity snapshot.
func (r *Registry) EntityQuery(key string, eid int64) (*Entity, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil, false
	}
	for _, d := range w.Dimensions {
		if e := d.Entities[eid]; e != nil {
			cp := *e
			return &cp, true
		}
	}
	return nil, false
}

// EntitiesNear returns entities within a radius of a point.
func (r *Registry) EntitiesNear(key string, dim int32, x, y, z, radius float32, limit int) []*Entity {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil
	}
	d := w.Dimensions[dim]
	if d == nil {
		return nil
	}
	rad2 := radius * radius
	out := make([]*Entity, 0, 16)
	for _, e := range d.Entities {
		dx := e.Pos[0] - x
		dy := e.Pos[1] - y
		dz := e.Pos[2] - z
		if dx*dx+dy*dy+dz*dz <= rad2 {
			cp := *e
			out = append(out, &cp)
			if limit > 0 && len(out) >= limit {
				break
			}
		}
	}
	return out
}

// PlayerQuery returns one player snapshot.
func (r *Registry) PlayerQuery(key string, eid int64) (*Player, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil, false
	}
	for _, d := range w.Dimensions {
		if p := d.Players[eid]; p != nil {
			cp := *p
			return &cp, true
		}
	}
	return nil, false
}

// InventoryQuery returns the slots of one window.
func (r *Registry) InventoryQuery(key string, eid int64, windowID int32) ([]bridge.Item, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil, false
	}
	for _, d := range w.Dimensions {
		if p := d.Players[eid]; p != nil {
			if inv, found := p.Inventories[windowID]; found {
				out := make([]bridge.Item, len(inv))
				copy(out, inv)
				return out, true
			}
		}
	}
	return nil, false
}

// WorldList returns a compact summary of every tracked world.
func (r *Registry) WorldList() []WorldSummary {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]WorldSummary, 0, len(r.worlds))
	for _, w := range r.worlds {
		chunks, entities := 0, 0
		for _, d := range w.Dimensions {
			chunks += len(d.Chunks)
			entities += len(d.Entities)
		}
		out = append(out, WorldSummary{
			Key: w.Key, Name: w.Name, Seed: w.Seed,
			Spawn: w.Spawn, Gamemode: w.Gamemode, Difficulty: w.Difficulty,
			Dimensions: len(w.Dimensions), Chunks: chunks, Entities: entities,
		})
	}
	return out
}

// WorldSummary is one row of the world list.
type WorldSummary struct {
	Key        string
	Name       string
	Seed       int64
	Spawn      BlockPos
	Gamemode   int32
	Difficulty int32
	Dimensions int
	Chunks     int
	Entities   int
}

// ---- retention janitor ----

// Sweep evicts stale data: chunks past retention, worlds nobody touched for
// twice the retention, entities unseen for two minutes. Returns the number of
// chunks evicted.
func (r *Registry) Sweep() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := nowNano()
	evicted := 0
	staleWorld := now - int64(r.dropWorldAfter)
	staleChunk := now - int64(r.retention)
	staleEntity := now - int64(2*time.Minute)
	for key, w := range r.worlds {
		if w.LastActive < staleWorld {
			delete(r.worlds, key)
			continue
		}
		for dimID, d := range w.Dimensions {
			// Entities: nothing moved for two minutes - drop silently.
			for eid, e := range d.Entities {
				if e.LastSeen < staleEntity {
					delete(d.Entities, eid)
					delete(d.Players, eid)
				}
			}
			// Chunks: retention TTL first, then hard cap with LRU eviction.
			if len(d.Chunks) > r.maxChunks {
				type ck struct {
					k  ChunkKey
					at int64
				}
				all := make([]ck, 0, len(d.Chunks))
				for k, c := range d.Chunks {
					all = append(all, ck{k, c.LastAccess})
				}
				sort.Slice(all, func(i, j int) bool { return all[i].at < all[j].at })
				over := len(all) - r.maxChunks
				for i := 0; i < over; i++ {
					delete(d.Chunks, all[i].k)
					evicted++
				}
			}
			for k, c := range d.Chunks {
				if c.LastAccess < staleChunk {
					delete(d.Chunks, k)
					evicted++
					if r.OnChunkUnload != nil {
						cb := r.OnChunkUnload
						r.mu.Unlock()
						cb(key, k.Dim, k.CX, k.CZ)
						r.mu.Lock()
					}
				}
			}
			_ = dimID
		}
	}
	return evicted
}

// StartJanitor runs Sweep on a ticker until stop closes.
func (r *Registry) StartJanitor(stop <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				r.Sweep()
			}
		}
	}()
}

// PlayerByName finds a tracked player by exact name in a world.
func (r *Registry) PlayerByName(key, name string) (*Player, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return nil, false
	}
	for _, d := range w.Dimensions {
		for _, p := range d.Players {
			if p.Name == name {
				cp := *p
				return &cp, true
			}
		}
	}
	return nil, false
}

// ForEachBlockEntity visits every block entity of one chunk.
func (r *Registry) ForEachBlockEntity(key string, dim, cx, cz int32, visit func(pos BlockPos, be *BlockEntityData)) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	w, ok := r.worlds[key]
	if !ok {
		return
	}
	d := w.Dimensions[dim]
	if d == nil {
		return
	}
	chunk := d.Chunks[ChunkKey{Dim: dim, CX: cx, CZ: cz}]
	if chunk == nil {
		return
	}
	chunk.LastAccess = nowNano()
	for pos, be := range chunk.BlockEntityMap {
		visit(pos, be)
	}
}
