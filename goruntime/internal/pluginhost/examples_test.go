package pluginhost

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/bridge"
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

// TestLoadShippedExamples loads the example plugins shipped in the repository
// (examples/plugins/go) through the real loader: if an example breaks, the
// release is broken too.
func TestLoadShippedExamples(t *testing.T) {
	examples := filepath.Join("..", "..", "..", "examples", "plugins", "go")
	if _, err := os.Stat(examples); err != nil {
		t.Skipf("examples dir not present from this test location: %v", err)
	}
	// Copy into a writable root: the host walks directories only.
	root := t.TempDir()
	entries, err := os.ReadDir(examples)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		src := filepath.Join(examples, e.Name())
		dst := filepath.Join(root, e.Name())
		if err := os.MkdirAll(dst, 0o755); err != nil {
			t.Fatal(err)
		}
		files, _ := os.ReadDir(src)
		for _, f := range files {
			data, err := os.ReadFile(filepath.Join(src, f.Name()))
			if err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(dst, f.Name()), data, 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}

	host := NewHost(&testLogger{}, &pluginapi.API{Worlds: &fakeWorlds{}, Proxy: &fakeProxy{}, Log: &testLogger{}}, root)
	host.LoadAll()

	status := host.Status()
	if len(status) < 3 {
		t.Fatalf("expected at least 3 example plugins, got %d", len(status))
	}
	for _, s := range status {
		if s.State != bridge.PluginEnabled {
			t.Fatalf("example %s failed to load: %s (state %d)", s.Name, s.Error, s.State)
		}
	}
}
