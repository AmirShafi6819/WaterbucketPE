package pluginapi

import (
	"fmt"
	"sync"
	"time"
)

// APIVersion is the plugin API generation this proxy build exposes. Plugins
// declaring this version (or the 1.0.0 legacy wildcard) load; anything else
// is rejected with a clear error.
const APIVersion = "1.6.0"

// WorldAccess is the read side of the live world model. The Go runtime wires
// its registry into this interface at startup; plugins never build it.
type WorldAccess interface {
	ListWorlds() []World
	GetWorld(key string) *World
	GetBlock(serverKey string, dim int32, x, y, z int32) Block
	IsChunkLoaded(serverKey string, dim, cx, cz int32) bool
	GetChunk(serverKey string, dim, cx, cz int32) (*Chunk, bool)
	GetBlockEntity(serverKey string, dim int32, x, y, z int32) (*BlockEntity, bool)
	GetEntity(serverKey string, eid int64) (*Entity, bool)
	GetEntitiesNear(serverKey string, dim int32, x, y, z, radius float32, limit int) []*Entity
	GetPlayer(serverKey string, eid int64) (*Player, bool)
	GetPlayerByName(serverKey, name string) (*Player, bool)
	GetInventory(serverKey string, eid int64, windowID int32) ([]Item, bool)
}

// ProxyAccess is the action side: real proxy operations performed by the Java
// core over the bridge RPC. Errors carry the proxy's failure message.
type ProxyAccess interface {
	SendMessage(playerName, message string) error
	SendTip(playerName, message string) error
	SendPopup(playerName, message, subtitle string) error
	SendTitle(playerName, title, subtitle string) error
	Kick(playerName, reason string) error
	Transfer(playerName, serverName string) error
	BroadcastMessage(message string) error
	DispatchCommand(commandLine string) error
	DispatchCommandAs(playerName, commandLine string) error
	GetOnlinePlayers() []string
	OnlineCount() int
	ProxyVersion() string
}

// Logger receives plugin log lines; the runtime forwards them into the proxy
// log with the plugin name attached.
type Logger interface {
	Debug(msg string)
	Info(msg string)
	Warn(msg string)
	Error(msg string)
}

// Plugin is the interface every Go plugin implements. OnEnable runs at load
// (or reload), OnDisable at unload/shutdown, and HandleEvent once per event
// in plugin load order - returning true cancels a cancellable event.
type Plugin interface {
	OnEnable(api *API)
	OnDisable()
	HandleEvent(event Event) bool
}

// API is the root facade handed to OnEnable.
type API struct {
	Worlds WorldAccess
	Proxy  ProxyAccess
	Log    Logger
}

// Version returns the plugin API version (1.6.0).
func (a *API) Version() string { return APIVersion }

// ListWorlds returns every currently visible world.
func (a *API) ListWorlds() []World { return a.Worlds.ListWorlds() }

// World returns one world by its downstream server key, or nil.
func (a *API) World(serverKey string) *World { return a.Worlds.GetWorld(serverKey) }

// GetBlock resolves one live block from the world model.
func (a *API) GetBlock(serverKey string, dim int32, x, y, z int32) Block {
	return a.Worlds.GetBlock(serverKey, dim, x, y, z)
}

// GetBlockEntity resolves one block entity, or nil.
func (a *API) GetBlockEntity(serverKey string, dim int32, x, y, z int32) *BlockEntity {
	be, ok := a.Worlds.GetBlockEntity(serverKey, dim, x, y, z)
	if !ok {
		return nil
	}
	return be
}

// GetEntity resolves one entity snapshot, or nil.
func (a *API) GetEntity(serverKey string, eid int64) *Entity {
	e, ok := a.Worlds.GetEntity(serverKey, eid)
	if !ok {
		return nil
	}
	return e
}

// GetEntitiesNear resolves entities within a radius (limit <= 0 = no limit).
func (a *API) GetEntitiesNear(serverKey string, dim int32, x, y, z, radius float32, limit int) []*Entity {
	return a.Worlds.GetEntitiesNear(serverKey, dim, x, y, z, radius, limit)
}

// GetPlayer resolves a tracked player by entity id, or nil.
func (a *API) GetPlayer(serverKey string, eid int64) *Player {
	p, ok := a.Worlds.GetPlayer(serverKey, eid)
	if !ok {
		return nil
	}
	return p
}

// PlayerByName resolves a tracked player by name (any world), or nil.
func (a *API) PlayerByName(name string) *Player {
	for _, w := range a.Worlds.ListWorlds() {
		if p, ok := a.Worlds.GetPlayerByName(w.Key, name); ok {
			return p
		}
	}
	return nil
}

// GetInventory resolves one window of a player.
func (a *API) GetInventory(serverKey string, eid int64, windowID int32) *Inventory {
	items, ok := a.Worlds.GetInventory(serverKey, eid, windowID)
	if !ok {
		return nil
	}
	p, _ := a.Worlds.GetPlayer(serverKey, eid)
	return &Inventory{WindowID: windowID, Items: items, Holder: p}
}

// Server returns the server facade (PocketMine-style access).
func (a *API) Server() *Server { return &Server{proxy: a.Proxy, worlds: a.Worlds} }

// RunLater schedules fn after delayMs on its own goroutine (panic-safe).
func (a *API) RunLater(delayMs int64, fn func()) {
	time.AfterFunc(time.Duration(delayMs)*time.Millisecond, func() {
		defer func() {
			if r := recover(); r != nil {
				if a.Log != nil {
					a.Log.Error(fmt.Sprintf("RunLater panicked: %v", r))
				}
			}
		}()
		fn()
	})
}

// Go runs fn on a panic-safe goroutine: long work belongs here, never inside
// HandleEvent (which must stay fast to keep the bridge verdict latency low).
func (a *API) Go(fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				if a.Log != nil {
					a.Log.Error(fmt.Sprintf("Go panicked: %v", r))
				}
			}
		}()
		fn()
	}()
}

// Server is the server facade: players, broadcasts, commands - all backed by
// live proxy calls.
type Server struct {
	proxy  ProxyAccess
	worlds WorldAccess
}

// OnlineCount reports the number of connected players.
func (s *Server) OnlineCount() int { return s.proxy.OnlineCount() }

// GetOnlinePlayers lists the connected player names.
func (s *Server) GetOnlinePlayers() []string { return s.proxy.GetOnlinePlayers() }

// GetPlayer resolves an online player by name, or nil.
func (s *Server) GetPlayer(name string) *Player { return playerByNameGlobal(s.worlds, name) }

// BroadcastMessage sends a message to every connected player.
func (s *Server) BroadcastMessage(message string) error { return s.proxy.BroadcastMessage(message) }

// DispatchCommand runs a command line as the console.
func (s *Server) DispatchCommand(commandLine string) error {
	return s.proxy.DispatchCommand(commandLine)
}

// DispatchCommandAs runs a command line as a player.
func (s *Server) DispatchCommandAs(playerName, commandLine string) error {
	return s.proxy.DispatchCommandAs(playerName, commandLine)
}

// GetAPIVersion returns the plugin API version of this build.
func (s *Server) GetAPIVersion() string { return APIVersion }

// GetProxyVersion returns the running proxy version.
func (s *Server) GetProxyVersion() string { return s.proxy.ProxyVersion() }

// playerByNameGlobal searches every world for a tracked player.
func playerByNameGlobal(worlds WorldAccess, name string) *Player {
	for _, w := range worlds.ListWorlds() {
		if p, ok := worlds.GetPlayerByName(w.Key, name); ok {
			return p
		}
	}
	return nil
}

// ---- player actions (every call is a real proxy operation) ----

// SendMessage sends a chat line to this player.
func (p *Player) SendMessage(api *API, message string) error {
	return api.Proxy.SendMessage(p.Name, message)
}

// SendTip sends a tip above the hotbar.
func (p *Player) SendTip(api *API, message string) error {
	return api.Proxy.SendTip(p.Name, message)
}

// SendPopup sends a popup with an optional subtitle.
func (p *Player) SendPopup(api *API, message, subtitle string) error {
	return api.Proxy.SendPopup(p.Name, message, subtitle)
}

// SendTitle sends a title with an optional subtitle.
func (p *Player) SendTitle(api *API, title, subtitle string) error {
	return api.Proxy.SendTitle(p.Name, title, subtitle)
}

// Kick disconnects the player with a reason.
func (p *Player) Kick(api *API, reason string) error {
	return api.Proxy.Kick(p.Name, reason)
}

// Transfer moves the player to another registered downstream server.
func (p *Player) Transfer(api *API, serverName string) error {
	return api.Proxy.Transfer(p.Name, serverName)
}

// World resolves the world the player currently plays in.
func (p *Player) World(api *API) *World {
	for _, w := range api.Worlds.ListWorlds() {
		if _, ok := api.Worlds.GetPlayerByName(w.Key, p.Name); ok {
			return api.Worlds.GetWorld(w.Key)
		}
	}
	return nil
}

// Inventory returns the player inventory window (windowID 12 = main inventory).
func (p *Player) Inventory(api *API, windowID int32) *Inventory {
	return api.GetInventory(p.WorldKey(), p.Eid, windowID)
}

// WorldKey returns the downstream server key this player is tracked under.
func (p *Player) WorldKey() string {
	return p.WorldKeyField
}

// ---- dispatch registry ----

// EventDispatch receives events; the runtime provides it.
type EventDispatch interface {
	Dispatch(event Event) bool // true when some plugin cancelled
}

// Mutex keeps a small shared state helper for plugins that need it.
type Mutex struct {
	mu sync.Mutex
}

// Lock locks the helper.
func (m *Mutex) Lock() { m.mu.Lock() }

// Unlock unlocks the helper.
func (m *Mutex) Unlock() { m.mu.Unlock() }
