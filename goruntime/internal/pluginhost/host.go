// Package pluginhost loads and runs Go plugins inside the runtime sidecar.
// Plugins live in directories under the plugins root: each directory carries
// a plugin.yml manifest (same shape as every other loader's manifest) plus
// the Go source files. Sources are interpreted by yaegi, so users ship plain
// .go files - no toolchain required - while the host itself is compiled Go.
package pluginhost

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/traefik/yaegi/interp"
	"github.com/traefik/yaegi/stdlib"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapibindings"
)

// PluginAPIVersion mirrors pluginapi.APIVersion: the generation this runtime
// accepts. Plugins may also declare the 1.0.0 legacy wildcard, which loads on
// every generation.
const PluginAPIVersion = pluginapi.APIVersion

// LegacyAPIVersion loads on every proxy generation.
const LegacyAPIVersion = "1.0.0"

// Host is the plugin manager of the Go runtime.
type Host struct {
	mu      sync.RWMutex
	plugins []*loaded
	logger  pluginapi.Logger
	api     *pluginapi.API
	rootDir string
}

// loaded is one plugin instance plus its manifest metadata.
type loaded struct {
	name       string
	version    string
	apiVersion string
	dir        string
	instance   pluginapi.Plugin
	err        error
	state      byte
}

// NewHost builds a host around a logger and the shared API facade.
func NewHost(logger pluginapi.Logger, api *pluginapi.API, rootDir string) *Host {
	return &Host{logger: logger, api: api, rootDir: rootDir}
}

// LoadAll loads every plugin directory under the root, in name order.
// Rejected plugins (bad manifest, unsupported api-version, broken source)
// surface in Status() and the logs, never as a crash.
func (h *Host) LoadAll() {
	entries, err := os.ReadDir(h.rootDir)
	if err != nil {
		h.logInfo("Go plugin directory %s missing or unreadable: %v", h.rootDir, err)
		return
	}
	dirs := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			dirs = append(dirs, e.Name())
		}
	}
	sort.Strings(dirs)
	for _, dir := range dirs {
		h.load(filepath.Join(h.rootDir, dir))
	}
}

// ValidateAPIVersion implements the proxy-wide api-version contract:
// current generation loads, the 1.0.0 legacy wildcard loads everywhere,
// anything else is rejected with the exact reason.
func ValidateAPIVersion(declared string) error {
	switch declared {
	case PluginAPIVersion:
		return nil
	case LegacyAPIVersion, "":
		return nil // missing/blank behaves exactly like the legacy wildcard
	default:
		return fmt.Errorf(
			"unsupported api-version %q: this build supports %q (or %q for legacy plugins that run on every generation)",
			declared, PluginAPIVersion, LegacyAPIVersion)
	}
}

// load loads one plugin directory.
func (h *Host) load(dir string) {
	manifest, err := readManifest(filepath.Join(dir, "plugin.yml"))
	if err != nil {
		h.record(&loaded{name: filepath.Base(dir), dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: %v", filepath.Base(dir), err)
		return
	}
	if err := ValidateAPIVersion(manifest.apiVersion); err != nil {
		h.record(&loaded{name: manifest.name, version: manifest.version, apiVersion: manifest.apiVersion, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: %v", manifest.name, err)
		return
	}

	i := interp.New(interp.Options{GoPath: os.Getenv("GOPATH")})
	if err := i.Use(stdlib.Symbols); err != nil {
		h.record(&loaded{name: manifest.name, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: stdlib symbols: %v", manifest.name, err)
		return
	}
	if err := i.Use(pluginapibindings.Symbols); err != nil {
		h.record(&loaded{name: manifest.name, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: api symbols: %v", manifest.name, err)
		return
	}

	mainFile := manifest.main
	if mainFile == "" {
		mainFile = "main.go"
	}
	source, err := os.ReadFile(filepath.Join(dir, mainFile))
	if err != nil {
		h.record(&loaded{name: manifest.name, version: manifest.version, apiVersion: manifest.apiVersion, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: %v", manifest.name, err)
		return
	}
	if _, err := i.Eval(string(source)); err != nil {
		h.record(&loaded{name: manifest.name, version: manifest.version, apiVersion: manifest.apiVersion, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: eval: %v", manifest.name, err)
		return
	}

	// Plugins export `var Plugin pluginapi.Plugin` (or any type implementing it).
	sym, err := i.Eval("Plugin")
	if err != nil {
		err = fmt.Errorf("plugin must expose a `var Plugin` implementing pluginapi.Plugin: %w", err)
		h.record(&loaded{name: manifest.name, version: manifest.version, apiVersion: manifest.apiVersion, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: %v", manifest.name, err)
		return
	}
	instance, ok := sym.Interface().(pluginapi.Plugin)
	if !ok {
		err := fmt.Errorf("exported var Plugin is %T, does not implement pluginapi.Plugin", sym.Interface())
		h.record(&loaded{name: manifest.name, version: manifest.version, apiVersion: manifest.apiVersion, dir: dir, err: err, state: bridge.PluginFailed})
		h.logError("plugin %s: %v", manifest.name, err)
		return
	}

	entry := &loaded{
		name:       manifest.name,
		version:    manifest.version,
		apiVersion: manifest.apiVersion,
		dir:        dir,
		instance:   instance,
		state:      bridge.PluginDisabled,
	}
	h.record(entry)
	h.enable(entry)
}

func (h *Host) record(entry *loaded) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.plugins = append(h.plugins, entry)
}

// enable runs OnEnable with panic isolation: a broken plugin fails alone.
func (h *Host) enable(entry *loaded) {
	defer func() {
		if r := recover(); r != nil {
			entry.err = fmt.Errorf("OnEnable panicked: %v", r)
			entry.state = bridge.PluginFailed
			h.logError("plugin %s: %v", entry.name, entry.err)
		}
	}()
	entry.instance.OnEnable(h.api)
	if entry.err == nil {
		entry.state = bridge.PluginEnabled
		h.logInfo("plugin %s %s (api %s) enabled", entry.name, entry.version, entry.apiVersion)
	}
}

// UnloadAll disables every plugin in reverse load order.
func (h *Host) UnloadAll() {
	h.mu.RLock()
	plugins := make([]*loaded, len(h.plugins))
	copy(plugins, h.plugins)
	h.mu.RUnlock()
	for i := len(plugins) - 1; i >= 0; i-- {
		entry := plugins[i]
		if entry.state != bridge.PluginEnabled {
			continue
		}
		func() {
			defer func() {
				if r := recover(); r != nil {
					h.logError("plugin %s OnDisable panicked: %v", entry.name, r)
				}
			}()
			entry.instance.OnDisable()
			entry.state = bridge.PluginDisabled
		}()
	}
}

// Dispatch delivers one event to every enabled plugin in load order and
// returns true as soon as a plugin cancels (later plugins never see it, which
// matches the semantics of the original software's event priority model).
func (h *Host) Dispatch(event pluginapi.Event) bool {
	h.mu.RLock()
	plugins := make([]*loaded, len(h.plugins))
	copy(plugins, h.plugins)
	h.mu.RUnlock()
	for _, entry := range plugins {
		if entry.state != bridge.PluginEnabled {
			continue
		}
		cancelled := h.invoke(entry, event)
		if cancelled {
			return true
		}
	}
	return false
}

func (h *Host) invoke(entry *loaded, event pluginapi.Event) (cancelled bool) {
	defer func() {
		if r := recover(); r != nil {
			h.logError("plugin %s panicked handling %s: %v", entry.name, event.EventName(), r)
			cancelled = false // fail-open: one broken handler never eats events
		}
	}()
	return entry.instance.HandleEvent(event)
}

// Status renders the plugin list for the proxy log and the /plugin command.
func (h *Host) Status() []bridge.PluginStatusEntry {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]bridge.PluginStatusEntry, 0, len(h.plugins))
	for _, p := range h.plugins {
		errMsg := ""
		if p.err != nil {
			errMsg = p.err.Error()
		}
		out = append(out, bridge.PluginStatusEntry{
			Name:       p.name,
			Version:    p.version,
			APIVersion: p.apiVersion,
			State:      p.state,
			Error:      errMsg,
		})
	}
	return out
}

// PluginCount reports enabled plugin count.
func (h *Host) PluginCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	n := 0
	for _, p := range h.plugins {
		if p.state == bridge.PluginEnabled {
			n++
		}
	}
	return n
}

// manifest is the parsed plugin.yml.
type manifest struct {
	name       string
	version    string
	apiVersion string
	main       string
}

// readManifest parses the YAML manifest with a dedicated small reader: the
// manifest has six known scalar keys, so a parser here beats a YAML
// dependency and accepts the same lenient syntax as the Java side (quotes
// optional, comments allowed, unknown keys ignored).
func readManifest(path string) (*manifest, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("missing plugin.yml: %w", err)
	}
	m := &manifest{}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		colon := strings.Index(line, ":")
		if colon <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:colon])
		value := strings.TrimSpace(line[colon+1:])
		value = strings.Trim(value, `"'`)
		switch key {
		case "name":
			m.name = value
		case "version":
			m.version = value
		case "api-version", "api":
			m.apiVersion = value
		case "main":
			m.main = value
		}
	}
	if m.name == "" {
		return nil, fmt.Errorf("plugin.yml is missing the name key")
	}
	return m, nil
}

func (h *Host) logInfo(format string, args ...interface{}) {
	if h.logger != nil {
		h.logger.Info(fmt.Sprintf(format, args...))
	}
}

func (h *Host) logError(format string, args ...interface{}) {
	if h.logger != nil {
		h.logger.Error(fmt.Sprintf(format, args...))
	}
}
