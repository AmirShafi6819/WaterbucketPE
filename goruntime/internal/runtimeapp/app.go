// Package runtimeapp wires the bridge transport, the world model, the plugin
// host and the RPC client into the running sidecar: one place that knows how
// a frame from the proxy turns into model updates, plugin events and answers.
package runtimeapp

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/nbt"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/pluginhost"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/world"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

// Query ids (Java -> Go).
const (
	QueryGetBlock     = 1
	QueryChunkLoaded  = 2
	QueryChunkInfo    = 3
	QueryBlockEntity  = 4
	QueryEntity       = 5
	QueryEntitiesNear = 6
	QueryPlayer       = 7
	QueryInventory    = 8
	QueryWorldList    = 9
	QueryWorldMeta    = 10
)

// Log levels for OpLog.
const (
	LogDebug = 0
	LogInfo  = 1
	LogWarn  = 2
	LogError = 3
)

// App is the running sidecar.
type App struct {
	registry *world.Registry
	host     *pluginhost.Host
	api      *pluginapi.API
	logFn    func(level int, msg string)

	writeMu sync.Mutex
	writer  *bufio.Writer

	conn     net.Conn
	running  atomic.Bool
	proxyVer string

	// asyncEvents delivers observation events to a small worker pool.
	asyncEvents chan pluginapi.Event
	// droppedEvents counts observations shed under pressure.
	droppedEvents atomic.Int64

	// rpc pending calls (Go -> Java).
	rpcMu      sync.Mutex
	rpcNext    uint32
	rpcPending map[uint32]chan *bridge.Reader
}

// Config carries the handshake-provided settings.
type Config struct {
	WorldView   bool
	Retention   uint32
	MaxChunks   uint32
	SyncTimeout uint32
	PluginsDir  string
}

// NewApp builds the app: world registry, plugin host, API facades.
func NewApp(cfg *Config, logFn func(level int, msg string)) *App {
	if cfg.PluginsDir == "" {
		cfg.PluginsDir = "plugins-go"
	}
	reg := world.NewRegistry(int(cfg.Retention), int(cfg.MaxChunks))
	logger := &bridgeLogger{fn: logFn}
	app := &App{
		registry:    reg,
		logFn:       logFn,
		asyncEvents: make(chan pluginapi.Event, 4096),
		rpcPending:  make(map[uint32]chan *bridge.Reader),
	}
	app.api = &pluginapi.API{
		Worlds: &worldAccess{reg: reg, app: app},
		Proxy:  &proxyAccess{app: app},
		Log:    logger,
	}
	app.host = pluginhost.NewHost(logger, app.api, cfg.PluginsDir)
	reg.OnChunkLoad = func(key string, dim int32, cx, cz int32) {
		app.fireAsync(&pluginapi.ChunkLoadEvent{ServerKey: key, Dim: dim, CX: cx, CZ: cz})
	}
	reg.OnChunkUnload = func(key string, dim int32, cx, cz int32) {
		app.fireAsync(&pluginapi.ChunkUnloadEvent{ServerKey: key, Dim: dim, CX: cx, CZ: cz})
	}
	return app
}

// bridgeLogger forwards plugin logs to the proxy log.
type bridgeLogger struct{ fn func(level int, msg string) }

func (l *bridgeLogger) Debug(msg string) { l.fn(LogDebug, msg) }
func (l *bridgeLogger) Info(msg string)  { l.fn(LogInfo, msg) }
func (l *bridgeLogger) Warn(msg string)  { l.fn(LogWarn, msg) }
func (l *bridgeLogger) Error(msg string) { l.fn(LogError, msg) }

// Run connects to the proxy bridge and serves until the connection dies or
// stop closes. It reconnects with backoff; the proxy decides the lifetime.
func (a *App) Run(address string, stop <-chan struct{}) {
	a.running.Store(true)
	// Two workers keep observation events flowing without blocking the reader.
	for i := 0; i < 2; i++ {
		go a.eventWorker()
	}
	stopJanitor := make(chan struct{})
	a.registry.StartJanitor(stopJanitor)
	defer close(stopJanitor)

	for a.running.Load() {
		select {
		case <-stop:
			a.running.Store(false)
			return
		default:
		}
		conn, err := net.DialTimeout(parseAddr(address), dialAddr(address), 5*time.Second)
		if err != nil {
			a.log(LogWarn, fmt.Sprintf("bridge dial %s failed: %v (retrying in 2s)", address, err))
			sleepSelect(stop, 2*time.Second)
			continue
		}
		a.serve(conn, stop)
		_ = conn.Close()
		if a.running.Load() {
			a.log(LogWarn, "bridge connection lost; reconnecting in 2s")
			sleepSelect(stop, 2*time.Second)
		}
	}
}

func sleepSelect(stop <-chan struct{}, d time.Duration) {
	select {
	case <-stop:
	case <-time.After(d):
	}
}

// parseAddr splits "unix:/path" or "tcp:host:port" (default tcp).
func parseAddr(address string) string {
	if strings.HasPrefix(address, "unix:") {
		return "unix"
	}
	return "tcp"
}

func dialAddr(address string) string {
	return strings.TrimPrefix(address, "unix:")
}

// serve runs one connection: handshake, read loop, teardown.
func (a *App) serve(conn net.Conn, stop <-chan struct{}) {
	a.conn = conn
	a.writer = bufio.NewWriterSize(conn, 256*1024)

	// Handshake: the proxy sends HELLO first; we answer and list our plugins.
	reader := bufio.NewReaderSize(conn, 256*1024)
	var scratch []byte
	frame, newScratch, err := bridge.ReadFrame(reader, scratch)
	scratch = newScratch
	if err != nil {
		a.log(LogError, fmt.Sprintf("bridge handshake read failed: %v", err))
		return
	}
	if err := a.handleHello(frame); err != nil {
		a.log(LogError, fmt.Sprintf("bridge handshake failed: %v", err))
		return
	}

	errCh := make(chan error, 1)
	go func() {
		for {
			frame, newScratch, err := bridge.ReadFrame(reader, scratch)
			scratch = newScratch
			if err != nil {
				errCh <- err
				return
			}
			if err := a.handleFrame(frame); err != nil {
				a.log(LogError, fmt.Sprintf("frame handling failed: %v", err))
				errCh <- err
				return
			}
		}
	}()

	// Ping the proxy so stalls surface within seconds.
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			a.running.Store(false)
			return
		case err := <-errCh:
			a.log(LogWarn, fmt.Sprintf("bridge read stopped: %v", err))
			return
		case <-ticker.C:
			w := bridge.NewWriter(8)
			w.Byte(bridge.OpPing)
			if err := a.writeFrame(w.Buf); err != nil {
				a.log(LogWarn, fmt.Sprintf("bridge write failed: %v", err))
				return
			}
		}
	}
}

func (a *App) handleHello(frame []byte) error {
	r := bridge.NewReader(frame)
	op, err := r.Byte()
	if err != nil {
		return err
	}
	if op != bridge.OpHello {
		return fmt.Errorf("expected HELLO, got opcode 0x%02X", op)
	}
	proto, err := r.Byte()
	if err != nil {
		return err
	}
	if proto != bridge.ProtocolVersion {
		return fmt.Errorf("proxy speaks bridge protocol %d, runtime supports %d", proto, bridge.ProtocolVersion)
	}
	a.proxyVer, err = r.String()
	if err != nil {
		return err
	}
	worldView, err := r.Bool()
	if err != nil {
		return err
	}
	retention, err := r.Uint32()
	if err != nil {
		return err
	}
	maxChunks, err := r.Uint32()
	if err != nil {
		return err
	}
	_, err = r.Uint32() // sync timeout (java side owns the wait)
	if err != nil {
		return err
	}

	// Recreate the registry with the negotiated bounds.
	a.registry = world.NewRegistry(int(retention), int(maxChunks))

	// Load plugins now: they land in HELLO_ACK, so the proxy knows exactly
	// what is running before the first delta arrives.
	a.host.LoadAll()

	w := bridge.NewWriter(256)
	bridge.EncodeHelloAck(w, fmt.Sprintf("go-%s (%s)", runtime.Version(), a.proxyVer), pluginapi.APIVersion, a.pluginNames())
	if err := a.writeFrame(w.Buf); err != nil {
		return err
	}
	a.sendPluginStatus()
	a.log(LogInfo, fmt.Sprintf("connected to proxy %s; world view %v; plugins loaded: %d", a.proxyVer, worldView, a.host.PluginCount()))
	return nil
}

func (a *App) pluginNames() []string {
	names := []string{}
	for _, s := range a.host.Status() {
		if s.State == bridge.PluginEnabled {
			names = append(names, s.Name)
		}
	}
	return names
}

func (a *App) sendPluginStatus() {
	w := bridge.NewWriter(512)
	bridge.EncodePluginStatus(w, a.host.Status())
	_ = a.writeFrame(w.Buf)
}

// writeFrame serializes writes; the reader loop, event workers and plugin
// RPCs all funnel through here.
func (a *App) writeFrame(payload []byte) error {
	if a.conn == nil || a.writer == nil {
		// No live connection (unit tests, standalone runs): responses drop.
		return nil
	}
	a.writeMu.Lock()
	defer a.writeMu.Unlock()
	return bridge.WriteFrame(a.writer, payload)
}

// handleFrame dispatches one proxy frame.
func (a *App) handleFrame(frame []byte) error {
	if len(frame) == 0 {
		return nil
	}
	switch frame[0] {
	case bridge.OpPing:
		w := bridge.NewWriter(2)
		w.Byte(bridge.OpPong)
		return a.writeFrame(w.Buf)
	case bridge.OpDelta:
		return a.handleDelta(frame[1:])
	case bridge.OpEventSync:
		return a.handleEventSync(frame[1:])
	case bridge.OpEventAsync:
		return a.handleEventAsync(frame[1:])
	case bridge.OpQuery:
		return a.handleQuery(frame[1:])
	case bridge.OpRpcResult:
		return a.handleRpcResult(frame[1:])
	case bridge.OpShutdown:
		a.log(LogInfo, "proxy requested shutdown")
		a.running.Store(false)
		return nil
	default:
		return nil // unknown opcodes are ignored for forward compatibility
	}
}

// handleDelta decodes one world observation and applies it to the model.
func (a *App) handleDelta(body []byte) error {
	r := bridge.NewReader(body)
	deltaType, err := r.Byte()
	if err != nil {
		return err
	}
	switch deltaType {
	case bridge.DeltaWorldMeta:
		key, err := r.String()
		if err != nil {
			return err
		}
		name, err := r.String()
		if err != nil {
			return err
		}
		seed, err := r.Int64()
		if err != nil {
			return err
		}
		sx, sy, sz, err := r.Vec3i()
		if err != nil {
			return err
		}
		gamemode, err := r.Int32()
		if err != nil {
			return err
		}
		difficulty, err := r.Int32()
		if err != nil {
			return err
		}
		ruleCount, err := r.Uint16()
		if err != nil {
			return err
		}
		gamerules := make(map[string]string, ruleCount)
		for i := 0; i < int(ruleCount); i++ {
			k, err := r.String()
			if err != nil {
				return err
			}
			v, err := r.String()
			if err != nil {
				return err
			}
			gamerules[k] = v
		}
		blockCount, err := r.Uint16()
		if err != nil {
			return err
		}
		blockTable := make(map[int32]world.PaletteEntry, blockCount)
		for i := 0; i < int(blockCount); i++ {
			rid, err := r.Int32()
			if err != nil {
				return err
			}
			bname, err := r.String()
			if err != nil {
				return err
			}
			blockTable[rid] = world.PaletteEntry{Name: bname}
		}
		a.registry.ApplyWorldMeta(key, name, seed, world.BlockPos{X: sx, Y: sy, Z: sz}, gamemode, difficulty, gamerules, blockTable)

	case bridge.DeltaChunk:
		key, err := r.String()
		if err != nil {
			return err
		}
		dim, err := r.Int32()
		if err != nil {
			return err
		}
		cx, err := r.Int32()
		if err != nil {
			return err
		}
		cz, err := r.Int32()
		if err != nil {
			return err
		}
		blob, err := r.Bytes()
		if err != nil {
			return err
		}
		blobCopy := make([]byte, len(blob))
		copy(blobCopy, blob)
		a.registry.ApplyChunk(key, dim, cx, cz, blobCopy)

	case bridge.DeltaBlockUpdate:
		key, err := r.String()
		if err != nil {
			return err
		}
		dim, err := r.Int32()
		if err != nil {
			return err
		}
		x, y, z, err := r.Vec3i()
		if err != nil {
			return err
		}
		_, err = r.Int32() // layer: model tracks latest state only
		if err != nil {
			return err
		}
		runtimeId, err := r.Int32()
		if err != nil {
			return err
		}
		a.registry.ApplyBlockUpdate(key, dim, x, y, z, runtimeId)

	case bridge.DeltaBlockEntity:
		key, err := r.String()
		if err != nil {
			return err
		}
		dim, err := r.Int32()
		if err != nil {
			return err
		}
		x, y, z, err := r.Vec3i()
		if err != nil {
			return err
		}
		typeName, err := r.String()
		if err != nil {
			return err
		}
		nbtBytes, err := r.Bytes()
		if err != nil {
			return err
		}
		parsed := parseNBT(nbtBytes)
		a.registry.ApplyBlockEntity(key, dim, x, y, z, typeName, parsed)

	case bridge.DeltaEntityAdd:
		fields, err := decodeEntityAdd(r)
		if err != nil {
			return err
		}
		a.registry.ApplyEntityAdd(fields.key, fields.dim, fields.eid, fields.typ,
			fields.x, fields.y, fields.z, fields.yaw, fields.pitch, fields.motion,
			fields.isPlayer, fields.name, fields.xuid, fields.flags)

	case bridge.DeltaEntityRemove:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		a.registry.ApplyEntityRemove(key, eid)

	case bridge.DeltaEntityMove:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		x, y, z, err := r.Vec3f()
		if err != nil {
			return err
		}
		yaw, err := r.Float32()
		if err != nil {
			return err
		}
		pitch, err := r.Float32()
		if err != nil {
			return err
		}
		_, err = r.Bool() // onGround: motion detail only
		if err != nil {
			return err
		}
		a.registry.ApplyEntityMove(key, eid, x, y, z, yaw, pitch, false)

	case bridge.DeltaEntityMotion:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		mx, my, mz, err := r.Vec3f()
		if err != nil {
			return err
		}
		a.registry.ApplyEntityMotion(key, eid, mx, my, mz)

	case bridge.DeltaEntityMeta:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		flags, err := r.Int64()
		if err != nil {
			return err
		}
		a.registry.ApplyEntityMeta(key, eid, uint64(flags))

	case bridge.DeltaEntityAttribute:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		attr, err := r.String()
		if err != nil {
			return err
		}
		value, err := r.Float32()
		if err != nil {
			return err
		}
		a.registry.ApplyEntityAttribute(key, eid, attr, value)

	case bridge.DeltaPlayerInfo:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		name, err := r.String()
		if err != nil {
			return err
		}
		xuid, err := r.String()
		if err != nil {
			return err
		}
		gamemode, err := r.Int32()
		if err != nil {
			return err
		}
		dim, err := r.Int32()
		if err != nil {
			return err
		}
		a.registry.ApplyPlayerInfo(key, eid, name, xuid, gamemode, dim)

	case bridge.DeltaPlayerRemove:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		a.registry.ApplyPlayerRemove(key, eid)

	case bridge.DeltaPlayerEquip:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		hotbar, err := r.Int32()
		if err != nil {
			return err
		}
		invSlot, err := r.Int32()
		if err != nil {
			return err
		}
		item, err := r.Item()
		if err != nil {
			return err
		}
		a.registry.ApplyEquip(key, eid, item, hotbar, invSlot)

	case bridge.DeltaContainer:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		_, err = r.Bool() // open/close
		if err != nil {
			return err
		}
		windowID, err := r.Int32()
		if err != nil {
			return err
		}
		_, err = r.String() // type name
		if err != nil {
			return err
		}
		_, _, _, err = r.Vec3i()
		if err != nil {
			return err
		}
		a.registry.ApplyContainer(key, eid, windowID, true, "", 0, 0, 0)

	case bridge.DeltaInventoryContent:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		windowID, err := r.Int32()
		if err != nil {
			return err
		}
		count, err := r.Uint16()
		if err != nil {
			return err
		}
		items := make(map[int32]bridge.Item, count)
		for i := 0; i < int(count); i++ {
			slot, err := r.Int32()
			if err != nil {
				return err
			}
			item, err := r.Item()
			if err != nil {
				return err
			}
			items[slot] = item
		}
		a.registry.ApplyInventoryContent(key, eid, windowID, items)

	case bridge.DeltaInventorySlot:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		windowID, err := r.Int32()
		if err != nil {
			return err
		}
		slot, err := r.Int32()
		if err != nil {
			return err
		}
		item, err := r.Item()
		if err != nil {
			return err
		}
		a.registry.ApplyInventorySlot(key, eid, windowID, slot, item)

	case bridge.DeltaWorldTime:
		key, err := r.String()
		if err != nil {
			return err
		}
		t, err := r.Int32()
		if err != nil {
			return err
		}
		a.registry.ApplyTime(key, t)

	case bridge.DeltaWorldDifficulty:
		key, err := r.String()
		if err != nil {
			return err
		}
		diff, err := r.Int32()
		if err != nil {
			return err
		}
		a.registry.ApplyDifficulty(key, diff)

	case bridge.DeltaWorldGameRule:
		key, err := r.String()
		if err != nil {
			return err
		}
		name, err := r.String()
		if err != nil {
			return err
		}
		value, err := r.String()
		if err != nil {
			return err
		}
		a.registry.ApplyGameRule(key, name, value)

	case bridge.DeltaWeather:
		key, err := r.String()
		if err != nil {
			return err
		}
		_, err = r.Int32()
		if err != nil {
			return err
		}
		raining, err := r.Bool()
		if err != nil {
			return err
		}
		thunder, err := r.Bool()
		if err != nil {
			return err
		}
		a.registry.ApplyWeather(key, raining, thunder)

	case bridge.DeltaSpawnPos:
		key, err := r.String()
		if err != nil {
			return err
		}
		_, err = r.Int32()
		if err != nil {
			return err
		}
		x, y, z, err := r.Vec3i()
		if err != nil {
			return err
		}
		a.registry.ApplySpawnPos(key, x, y, z)

	case bridge.DeltaServerDown:
		key, err := r.String()
		if err != nil {
			return err
		}
		a.registry.ApplyServerDown(key)
		a.fireAsync(&pluginapi.WorldUnloadEvent{ServerKey: key})

	case bridge.DeltaPlayerState:
		key, err := r.String()
		if err != nil {
			return err
		}
		eid, err := r.Int64()
		if err != nil {
			return err
		}
		x, y, z, err := r.Vec3f()
		if err != nil {
			return err
		}
		yaw, err := r.Float32()
		if err != nil {
			return err
		}
		pitch, err := r.Float32()
		if err != nil {
			return err
		}
		a.registry.ApplyEntityMove(key, eid, x, y, z, yaw, pitch, true)
	}
	return nil
}

type entityAddFields struct {
	key      string
	dim      int32
	eid      int64
	typ      string
	x, y, z  float32
	yaw      float32
	pitch    float32
	motion   [3]float32
	isPlayer bool
	name     string
	xuid     string
	flags    uint64
}

func decodeEntityAdd(r *bridge.Reader) (entityAddFields, error) {
	var f entityAddFields
	var err error
	if f.key, err = r.String(); err != nil {
		return f, err
	}
	if f.dim, err = r.Int32(); err != nil {
		return f, err
	}
	if f.eid, err = r.Int64(); err != nil {
		return f, err
	}
	if f.typ, err = r.String(); err != nil {
		return f, err
	}
	if f.x, f.y, f.z, err = r.Vec3f(); err != nil {
		return f, err
	}
	if f.yaw, err = r.Float32(); err != nil {
		return f, err
	}
	if f.pitch, err = r.Float32(); err != nil {
		return f, err
	}
	if f.motion[0], f.motion[1], f.motion[2], err = r.Vec3f(); err != nil {
		return f, err
	}
	var b byte
	if b, err = r.Byte(); err != nil {
		return f, err
	}
	f.isPlayer = b != 0
	if f.name, err = r.String(); err != nil {
		return f, err
	}
	if f.xuid, err = r.String(); err != nil {
		return f, err
	}
	var flags int64
	if flags, err = r.Int64(); err != nil {
		return f, err
	}
	f.flags = uint64(flags)
	return f, nil
}

// remainingFields reads typed fields until the payload ends: events and
// queries carry a variable field count, and a fixed count would reject
// truncated-but-valid frames.
func remainingFields(r *bridge.Reader) ([]bridge.Field, error) {
	fields := []bridge.Field{}
	for r.Remaining() > 0 {
		f, err := r.Field()
		if err != nil {
			return nil, err
		}
		fields = append(fields, f)
	}
	return fields, nil
}

// parseNBT decodes a network NBT payload into a plain map; failures yield nil.
func parseNBT(payload []byte) map[string]interface{} {
	if len(payload) == 0 {
		return nil
	}
	reader := nbt.NewReader(payload)
	root, err := reader.ReadRoot()
	if err != nil {
		return nil
	}
	return nbtToPlain(root).(map[string]interface{})
}

// nbtToPlain converts nbt value trees into plain Go values so plugins never
// see internal types across the API boundary.
func nbtToPlain(v interface{}) interface{} {
	switch value := v.(type) {
	case nbt.Compound:
		out := make(map[string]interface{}, len(value))
		for k, item := range value {
			out[k] = nbtToPlain(item)
		}
		return out
	case []interface{}:
		out := make([]interface{}, len(value))
		for i, item := range value {
			out[i] = nbtToPlain(item)
		}
		return out
	default:
		return v
	}
}

// toAPIItem converts a wire item into the plugin API shape.
func toAPIItem(item bridge.Item) pluginapi.Item {
	out := pluginapi.Item{
		Identifier: item.Identifier,
		Damage:     item.Damage,
		Count:      item.Count,
		NetID:      item.NetID,
	}
	if item.HasNBT && len(item.NBT) > 0 {
		if parsed := parseNBT(item.NBT); parsed != nil {
			out.NBT = parsed
		}
	}
	return out
}

// ---- events ----

// fireAsync queues an observation event; the pool drains it.
func (a *App) fireAsync(event pluginapi.Event) {
	select {
	case a.asyncEvents <- event:
	default:
		a.droppedEvents.Add(1)
	}
}

func (a *App) eventWorker() {
	for a.running.Load() {
		event := <-a.asyncEvents
		a.host.Dispatch(event)
	}
}

// handleEventSync answers a cancellable event dispatch.
func (a *App) handleEventSync(body []byte) error {
	r := bridge.NewReader(body)
	callID, err := r.Uint32()
	if err != nil {
		return err
	}
	eventID, err := r.Uint16()
	if err != nil {
		return err
	}
	fields, err := remainingFields(r)
	if err != nil {
		return err
	}
	event := a.buildEvent(eventID, fields)
	cancelled := false
	if event != nil {
		cancelled = a.host.Dispatch(event)
	}
	w := bridge.NewWriter(32)
	w.Byte(bridge.OpEventResult)
	w.Uint32(callID)
	w.Bool(cancelled)
	return a.writeFrame(w.Buf)
}

// handleEventAsync queues an observation event.
func (a *App) handleEventAsync(body []byte) error {
	r := bridge.NewReader(body)
	eventID, err := r.Uint16()
	if err != nil {
		return err
	}
	fields, err := remainingFields(r)
	if err != nil {
		return err
	}
	event := a.buildEvent(eventID, fields)
	if event != nil {
		a.fireAsync(event)
	}
	return nil
}

// field accessors for the positional event layouts.
func fStr(fields []bridge.Field, i int) string {
	if i < len(fields) && fields[i].Type == bridge.TypeStr {
		return fields[i].Str
	}
	return ""
}

func fI32(fields []bridge.Field, i int) int32 {
	if i < len(fields) && fields[i].Type == bridge.TypeI32 {
		return fields[i].I32
	}
	return 0
}

func fI64(fields []bridge.Field, i int) int64 {
	if i < len(fields) && fields[i].Type == bridge.TypeI64 {
		return fields[i].I64
	}
	return 0
}

func fF32(fields []bridge.Field, i int) float32 {
	if i < len(fields) && fields[i].Type == bridge.TypeF32 {
		return fields[i].F32
	}
	return 0
}

func fBool(fields []bridge.Field, i int) bool {
	if i < len(fields) && fields[i].Type == bridge.TypeBool {
		return fields[i].Bool
	}
	return false
}

func fPos(fields []bridge.Field, i int) pluginapi.PosI {
	if i < len(fields) && fields[i].Type == bridge.TypeVec3i {
		return pluginapi.PosI{X: fields[i].Xi, Y: fields[i].Yi, Z: fields[i].Zi}
	}
	return pluginapi.PosI{}
}

func fVec3(fields []bridge.Field, i int) pluginapi.Vec3 {
	if i < len(fields) && fields[i].Type == bridge.TypeVec3f {
		return pluginapi.Vec3{X: fields[i].Xf, Y: fields[i].Yf, Z: fields[i].Zf}
	}
	return pluginapi.Vec3{}
}

func fItem(fields []bridge.Field, i int) pluginapi.Item {
	if i < len(fields) && fields[i].Type == bridge.TypeItem {
		return toAPIItem(fields[i].ItemVal)
	}
	return pluginapi.Item{}
}

// player resolves the player of an event by name (field 2 in player events).
func (a *App) player(fields []bridge.Field) *pluginapi.Player {
	name := fStr(fields, 2)
	if name == "" {
		return nil
	}
	if p, ok := a.registry.PlayerByName(fStr(fields, 0), name); ok {
		return a.toAPIPlayer(fStr(fields, 0), p)
	}
	// Untracked player (e.g. event fired before the model knew them): a
	// name-only view still lets plugins act via the proxy facade.
	return &pluginapi.Player{Entity: pluginapi.Entity{IsPlayer: true, Name: name}, WindowID: -1}
}

func (a *App) toAPIPlayer(serverKey string, p *world.Player) *pluginapi.Player {
	return &pluginapi.Player{
		Entity: pluginapi.Entity{
			Eid:      p.Eid,
			Type:     p.Type,
			Dim:      p.Dim,
			X:        p.Pos[0],
			Y:        p.Pos[1],
			Z:        p.Pos[2],
			MotX:     p.Motion[0],
			MotY:     p.Motion[1],
			MotZ:     p.Motion[2],
			Yaw:      p.Yaw,
			Pitch:    p.Pitch,
			Flags:    p.Flags,
			Attrs:    p.Attrs,
			IsPlayer: true,
			Name:     p.Name,
			Xuid:     p.Xuid,
		},
		Gamemode:      p.Gamemode,
		HeldItem:      toAPIItem(p.HeldItem),
		WindowID:      -1,
		WorldKeyField: serverKey,
	}
}

// buildEvent constructs the plugin event for one wire event id.
func (a *App) buildEvent(eventID uint16, fields []bridge.Field) pluginapi.Event {
	switch eventID {
	case bridge.EventWorldInit:
		return &pluginapi.WorldInitEvent{
			ServerKey: fStr(fields, 0), WorldName: fStr(fields, 1), Seed: fI64(fields, 2),
			Spawn: fPos(fields, 3), Gamemode: fI32(fields, 4), Difficulty: fI32(fields, 5),
		}
	case bridge.EventWorldUnload:
		return &pluginapi.WorldUnloadEvent{ServerKey: fStr(fields, 0)}
	case bridge.EventChunkSend:
		return &pluginapi.ChunkSendEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), CX: fI32(fields, 2),
			CZ: fI32(fields, 3), PlayerName: fStr(fields, 4),
		}
	case bridge.EventTimeChange:
		return &pluginapi.TimeChangeEvent{ServerKey: fStr(fields, 0), Time: fI32(fields, 1)}
	case bridge.EventWeatherChange:
		return &pluginapi.WeatherChangeEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Raining: fBool(fields, 2), Thunder: fBool(fields, 3),
		}
	case bridge.EventDifficultyChange:
		return &pluginapi.DifficultyChangeEvent{ServerKey: fStr(fields, 0), Difficulty: fI32(fields, 1)}
	case bridge.EventGameRuleChange:
		return &pluginapi.GameRuleChangeEvent{ServerKey: fStr(fields, 0), Name: fStr(fields, 1), Value: fStr(fields, 2)}
	case bridge.EventSpawnPositionChange:
		return &pluginapi.SpawnPositionChangeEvent{ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Pos: fPos(fields, 2)}

	case bridge.EventBlockBreak:
		return &pluginapi.BlockBreakEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Pos: fPos(fields, 3), Item: fItem(fields, 4), Face: fI32(fields, 5),
		}
	case bridge.EventBlockPlace:
		return &pluginapi.BlockPlaceEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Pos: fPos(fields, 3), Item: fItem(fields, 4), Face: fI32(fields, 5),
		}
	case bridge.EventBlockStartBreak:
		return &pluginapi.BlockStartBreakEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Pos: fPos(fields, 3), Item: fItem(fields, 4), Face: fI32(fields, 5),
		}
	case bridge.EventBlockUpdate:
		pos := fPos(fields, 2)
		name, _ := a.registry.BlockQuery(fStr(fields, 0), fI32(fields, 1), pos.X, pos.Y, pos.Z)
		return &pluginapi.BlockUpdateEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Pos: pos,
			RuntimeID: fI32(fields, 3), BlockName: fStr(fields, 4), OldName: name.Name, Layer: fI32(fields, 5),
		}
	case bridge.EventSignChange:
		lines := [4]string{}
		if raw := signLines(fields, 4); raw != nil {
			parts := strings.SplitN(string(raw), "\x00", 4)
			copy(lines[:], parts)
		}
		return &pluginapi.SignChangeEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Pos: fPos(fields, 3), Lines: lines,
		}

	case bridge.EventEntitySpawn:
		pos := fVec3(fields, 4)
		return &pluginapi.EntitySpawnEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1),
			Entity: pluginapi.Entity{
				Eid: fI64(fields, 2), Type: fStr(fields, 3),
				X: pos.X, Y: pos.Y, Z: pos.Z,
				IsPlayer: fBool(fields, 5), Name: fStr(fields, 6),
			},
		}
	case bridge.EventEntityDespawn:
		return &pluginapi.EntityDespawnEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2), Type: fStr(fields, 3),
		}
	case bridge.EventEntityMove:
		pos := fVec3(fields, 3)
		return &pluginapi.EntityMoveEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1),
			Entity: pluginapi.Entity{Eid: fI64(fields, 2), X: pos.X, Y: pos.Y, Z: pos.Z,
				Yaw: fF32(fields, 4), Pitch: fF32(fields, 5)},
			OnGround: fBool(fields, 6),
		}
	case bridge.EventEntityMotion:
		return &pluginapi.EntityMotionEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2), Motion: fVec3(fields, 3),
		}
	case bridge.EventEntityEffect:
		return &pluginapi.EntityEffectEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2),
			Action: fI32(fields, 3), EffectID: fI32(fields, 4),
			Amplifier: fI32(fields, 5), Duration: fI32(fields, 6),
		}
	case bridge.EventEntityHealthChange:
		return &pluginapi.EntityHealthChangeEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2),
			Old: fF32(fields, 3), New: fF32(fields, 4),
		}
	case bridge.EventEntityDamage:
		return &pluginapi.EntityDamageEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2),
			Amount: fF32(fields, 4), Old: fF32(fields, 5), New: fF32(fields, 6),
		}
	case bridge.EventEntityRegainHealth:
		return &pluginapi.EntityRegainHealthEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2),
			Amount: fF32(fields, 3), Old: fF32(fields, 4), New: fF32(fields, 5),
		}
	case bridge.EventEntityDataUpdate:
		return &pluginapi.EntityDataUpdateEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2),
			Flags: uint64(fI64(fields, 3)),
		}
	case bridge.EventEntityTeleport:
		return &pluginapi.EntityTeleportEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Eid: fI64(fields, 2), Pos: fVec3(fields, 3),
		}

	case bridge.EventPlayerInteract:
		return &pluginapi.PlayerInteractEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Pos: fPos(fields, 3), Face: fI32(fields, 4), Item: fItem(fields, 5), Action: fI32(fields, 6),
		}
	case bridge.EventPlayerItemHeld:
		return &pluginapi.PlayerItemHeldEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Item: fItem(fields, 3), HotbarSlot: fI32(fields, 4), InventorySlot: fI32(fields, 5),
		}
	case bridge.EventPlayerDropItem:
		return &pluginapi.PlayerDropItemEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			Item: fItem(fields, 3), Count: fI32(fields, 4),
		}
	case bridge.EventPlayerToggleSneak:
		return &pluginapi.PlayerToggleSneakEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Sneaking: fBool(fields, 3),
		}
	case bridge.EventPlayerToggleSprint:
		return &pluginapi.PlayerToggleSprintEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Sprinting: fBool(fields, 3),
		}
	case bridge.EventPlayerAnimation:
		return &pluginapi.PlayerAnimationEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Action: fI32(fields, 3),
		}
	case bridge.EventPlayerDeath:
		return &pluginapi.PlayerDeathEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
		}
	case bridge.EventPlayerRespawn:
		return &pluginapi.PlayerRespawnEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Pos: fVec3(fields, 3),
		}
	case bridge.EventPlayerGameModeChange:
		return &pluginapi.PlayerGameModeChangeEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Gamemode: fI32(fields, 3),
		}
	case bridge.EventPlayerChangeDimension:
		return &pluginapi.PlayerChangeDimensionEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			FromDim: fI32(fields, 3), ToDim: fI32(fields, 4),
		}
	case bridge.EventPlayerFormRespond:
		return &pluginapi.PlayerFormRespondEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			FormID: fI32(fields, 3), FormData: fStr(fields, 4),
		}
	case bridge.EventPlayerInteractEntity:
		target := a.resolveEntity(fStr(fields, 0), fI64(fields, 3))
		return &pluginapi.PlayerInteractEntityEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Target: target,
		}
	case bridge.EventPlayerMouseOver:
		target := a.resolveEntity(fStr(fields, 0), fI64(fields, 3))
		return &pluginapi.PlayerMouseOverEntityEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Target: target,
		}
	case bridge.EventPlayerBedEnter:
		return &pluginapi.PlayerBedEnterEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), Pos: fPos(fields, 3),
		}
	case bridge.EventPlayerBedLeave:
		return &pluginapi.PlayerBedLeaveEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
		}

	case bridge.EventInventoryOpen:
		return &pluginapi.InventoryOpenEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			WindowID: fI32(fields, 3), TypeName: fStr(fields, 4), Pos: fPos(fields, 5),
		}
	case bridge.EventInventoryClose:
		return &pluginapi.InventoryCloseEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields), WindowID: fI32(fields, 3),
		}
	case bridge.EventInventoryUpdate:
		return &pluginapi.InventoryUpdateEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			WindowID: fI32(fields, 3), Slot: fI32(fields, 4), Item: fItem(fields, 5),
		}
	case bridge.EventCraftItem:
		return &pluginapi.CraftItemEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			RecipeNetID: fI32(fields, 3), Actions: fStr(fields, 4),
		}
	case bridge.EventInventoryTransaction:
		return &pluginapi.InventoryTransactionEvent{
			ServerKey: fStr(fields, 0), Dim: fI32(fields, 1), Player: a.player(fields),
			TxnType: fI32(fields, 3), ActionType: fI32(fields, 4),
		}
	}
	return nil
}

func signLines(fields []bridge.Field, i int) []byte {
	if i < len(fields) && fields[i].Type == bridge.TypeBytes {
		return fields[i].Bytes
	}
	return nil
}

func (a *App) resolveEntity(serverKey string, eid int64) *pluginapi.Entity {
	if e, ok := a.registry.EntityQuery(serverKey, eid); ok {
		return &pluginapi.Entity{
			Eid: e.Eid, Type: e.Type, Dim: e.Dim,
			X: e.Pos[0], Y: e.Pos[1], Z: e.Pos[2],
			MotX: e.Motion[0], MotY: e.Motion[1], MotZ: e.Motion[2],
			Yaw: e.Yaw, Pitch: e.Pitch, Flags: e.Flags, Attrs: e.Attrs,
			IsPlayer: e.IsPlayer, Name: e.Name, Xuid: e.Xuid,
		}
	}
	return nil
}

// ---- queries (Java -> Go) ----

func (a *App) handleQuery(body []byte) error {
	r := bridge.NewReader(body)
	callID, err := r.Uint32()
	if err != nil {
		return err
	}
	queryType, err := r.Byte()
	if err != nil {
		return err
	}
	fields, err := remainingFields(r)
	if err != nil {
		return err
	}

	w := bridge.NewWriter(512)
	w.Byte(bridge.OpQueryResult)
	w.Uint32(callID)

	key := fStr(fields, 0)
	switch queryType {
	case QueryGetBlock:
		dim := fI32(fields, 1)
		pos := fPos(fields, 2)
		entry, loaded := a.registry.BlockQuery(key, dim, pos.X, pos.Y, pos.Z)
		w.Byte(bridge.QueryOK)
		if !loaded {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: entry.Name})
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: propsJSON(entry.States)})
		}
	case QueryChunkLoaded:
		loaded := a.registry.ChunkLoaded(key, fI32(fields, 1), fI32(fields, 2), fI32(fields, 3))
		w.Byte(bridge.QueryOK)
		w.Field(bridge.Field{Type: bridge.TypeBool, Bool: loaded})
	case QueryChunkInfo:
		subs, entities, ok := a.registry.ChunkStats(key, fI32(fields, 1), fI32(fields, 2), fI32(fields, 3))
		w.Byte(bridge.QueryOK)
		if !ok {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeI32, I32: int32(subs)})
			w.Field(bridge.Field{Type: bridge.TypeI32, I32: int32(entities)})
		}
	case QueryBlockEntity:
		dim := fI32(fields, 1)
		pos := fPos(fields, 2)
		be, ok := a.registry.BlockEntityQuery(key, dim, pos.X, pos.Y, pos.Z)
		w.Byte(bridge.QueryOK)
		if !ok || be == nil {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: be.Type})
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: mapJSON(be.NBT)})
		}
	case QueryEntity:
		e, ok := a.registry.EntityQuery(key, fI64(fields, 1))
		w.Byte(bridge.QueryOK)
		if !ok {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: entityJSON(e, nil)})
		}
	case QueryEntitiesNear:
		entities := a.registry.EntitiesNear(key, fI32(fields, 1),
			fF32(fields, 2), fF32(fields, 3), fF32(fields, 4), fF32(fields, 5), int(fI32(fields, 6)))
		list := make([]string, 0, len(entities))
		for _, e := range entities {
			list = append(list, entityJSON(e, nil))
		}
		w.Byte(bridge.QueryOK)
		w.Field(bridge.Field{Type: bridge.TypeStr, Str: "[" + strings.Join(list, ",") + "]"})
	case QueryPlayer:
		p, ok := a.registry.PlayerQuery(key, fI64(fields, 1))
		w.Byte(bridge.QueryOK)
		if !ok {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: playerJSON(p, key)})
		}
	case QueryInventory:
		items, ok := a.registry.InventoryQuery(key, fI64(fields, 1), fI32(fields, 2))
		w.Byte(bridge.QueryOK)
		if !ok {
			w.Byte(bridge.QueryNotFound)
		} else {
			encoded := make([]string, 0, len(items))
			for _, item := range items {
				encoded = append(encoded, itemJSON(item))
			}
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: "[" + strings.Join(encoded, ",") + "]"})
		}
	case QueryWorldList:
		summaries := a.registry.WorldList()
		data, _ := json.Marshal(summaries)
		w.Byte(bridge.QueryOK)
		w.Field(bridge.Field{Type: bridge.TypeStr, Str: string(data)})
	case QueryWorldMeta:
		worldRef := a.registry.World(key)
		w.Byte(bridge.QueryOK)
		if worldRef == nil {
			w.Byte(bridge.QueryNotFound)
		} else {
			w.Field(bridge.Field{Type: bridge.TypeStr, Str: worldMetaJSON(worldRef)})
		}
	default:
		w.Byte(bridge.QueryUnavailable)
	}
	return a.writeFrame(w.Buf)
}

func propsJSON(states map[string]string) string {
	if len(states) == 0 {
		return "{}"
	}
	data, _ := json.Marshal(states)
	return string(data)
}

func mapJSON(m map[string]interface{}) string {
	if m == nil {
		return "{}"
	}
	data, _ := json.Marshal(m)
	return string(data)
}

func itemJSON(item bridge.Item) string {
	nbtStr := "{}"
	if item.HasNBT && len(item.NBT) > 0 {
		if parsed := parseNBT(item.NBT); parsed != nil {
			nbtStr = mapJSON(parsed)
		}
	}
	return fmt.Sprintf(`{"name":%q,"damage":%d,"count":%d,"netId":%d,"nbt":%s}`,
		item.Identifier, item.Damage, item.Count, item.NetID, nbtStr)
}

func entityJSON(e *world.Entity, extra map[string]interface{}) string {
	base := fmt.Sprintf(`{"eid":%d,"type":%q,"dim":%d,"x":%f,"y":%f,"z":%f,"yaw":%f,"pitch":%f,"flags":%d,"isPlayer":%t,"name":%q,"xuid":%q`,
		e.Eid, e.Type, e.Dim, e.Pos[0], e.Pos[1], e.Pos[2], e.Yaw, e.Pitch, e.Flags, e.IsPlayer, e.Name, e.Xuid)
	if len(e.Attrs) > 0 {
		attrs, _ := json.Marshal(e.Attrs)
		base += `,"attrs":` + string(attrs)
	}
	if extra != nil {
		more, _ := json.Marshal(extra)
		base = strings.TrimSuffix(base, "}") + "," + strings.TrimPrefix(string(more), "{")
	}
	return base + "}"
}

func playerJSON(p *world.Player, serverKey string) string {
	return entityJSON(&p.Entity, map[string]interface{}{
		"gamemode": p.Gamemode,
		"heldItem": itemJSON(p.HeldItem),
		"worldKey": serverKey,
	})
}

func worldMetaJSON(w *world.World) string {
	rules, _ := json.Marshal(w.Gamerules)
	return fmt.Sprintf(`{"key":%q,"name":%q,"seed":%d,"spawn":{"x":%d,"y":%d,"z":%d},"gamemode":%d,"difficulty":%d,"time":%d,"raining":%t,"thunder":%t,"gamerules":%s}`,
		w.Key, w.Name, w.Seed, w.Spawn.X, w.Spawn.Y, w.Spawn.Z, w.Gamemode, w.Difficulty, w.Time, w.Raining, w.Thunder, rules)
}

// ---- RPC (Go -> Java) ----

// Call performs one proxy-side operation and returns its result fields.
func (a *App) Call(obj, method string, fields []bridge.Field, timeout time.Duration) ([]bridge.Field, error) {
	if a.conn == nil {
		return nil, fmt.Errorf("bridge is not connected")
	}
	a.rpcMu.Lock()
	a.rpcNext++
	callID := a.rpcNext
	pending := make(chan *bridge.Reader, 1)
	a.rpcPending[callID] = pending
	a.rpcMu.Unlock()

	w := bridge.NewWriter(256)
	w.Byte(bridge.OpRpcCall)
	w.Uint32(callID)
	w.String(obj)
	w.String(method)
	for _, f := range fields {
		w.Field(f)
	}
	if err := a.writeFrame(w.Buf); err != nil {
		a.rpcMu.Lock()
		delete(a.rpcPending, callID)
		a.rpcMu.Unlock()
		return nil, err
	}

	select {
	case resp := <-pending:
		status, _ := resp.Byte()
		if status != 0 {
			msg, _ := resp.String()
			if msg == "" {
				msg = "proxy call failed"
			}
			return nil, fmt.Errorf("%s", msg)
		}
		results := []bridge.Field{}
		for resp.Remaining() > 0 {
			f, err := resp.Field()
			if err != nil {
				break
			}
			results = append(results, f)
		}
		return results, nil
	case <-time.After(timeout):
		a.rpcMu.Lock()
		delete(a.rpcPending, callID)
		a.rpcMu.Unlock()
		return nil, fmt.Errorf("proxy call %s.%s timed out", obj, method)
	}
}

func (a *App) handleRpcResult(body []byte) error {
	r := bridge.NewReader(body)
	callID, err := r.Uint32()
	if err != nil {
		return err
	}
	a.rpcMu.Lock()
	pending := a.rpcPending[callID]
	delete(a.rpcPending, callID)
	a.rpcMu.Unlock()
	if pending != nil {
		// Hand the remaining payload to the waiting caller.
		result := bridge.NewReader(body[r.Pos:])
		select {
		case pending <- result:
		default:
		}
	}
	return nil
}

// log emits a sidecar log line into the proxy log.
func (a *App) log(level int, msg string) {
	if a.logFn != nil {
		a.logFn(level, msg)
	}
}

// Shutdown disables plugins and closes the connection.
func (a *App) Shutdown() {
	a.running.Store(false)
	a.host.UnloadAll()
	if a.conn != nil {
		_ = a.conn.Close()
	}
}

// PluginsDir resolves relative plugin directories against WATERBUCKET_HOME.
func PluginsDir(base string) string {
	if filepath.IsAbs(base) {
		return base
	}
	return filepath.Join(os.Getenv("WATERBUCKET_HOME"), base)
}
