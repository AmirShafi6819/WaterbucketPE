// Command goruntime is the WaterbucketPE Go runtime sidecar: it connects to
// the proxy's bridge socket, maintains the live world model, hosts Go plugins
// and answers world queries. The proxy spawns this binary (embedded in the
// release jar); operators only interact with it through proxy config.
package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/runtimeapp"
)

func main() {
	bridgeAddr := flag.String("bridge", "", "bridge address: unix:/path/to.sock or tcp:127.0.0.1:port")
	pluginsDir := flag.String("plugins", "plugins-go", "plugin directory root")
	flag.Parse()

	if *bridgeAddr == "" {
		fmt.Fprintln(os.Stderr, "goruntime: -bridge is required (unix:/path or tcp:addr)")
		os.Exit(2)
	}

	cfg := &runtimeapp.Config{
		WorldView:  true,
		PluginsDir: *pluginsDir,
	}
	app := runtimeapp.NewApp(cfg, func(level int, msg string) {
		switch level {
		case runtimeapp.LogWarn:
			fmt.Printf("[goruntime][WARN] %s\n", msg)
		case runtimeapp.LogError:
			fmt.Fprintf(os.Stderr, "[goruntime][ERROR] %s\n", msg)
		case runtimeapp.LogDebug:
			// debug stays silent unless the proxy asks for it
		default:
			fmt.Printf("[goruntime][INFO] %s\n", msg)
		}
	})

	stop := make(chan struct{})
	sigs := make(chan os.Signal, 2)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		close(stop)
		app.Shutdown()
	}()

	if dir := os.Getenv("WATERBUCKET_HOME"); dir != "" && !filepath.IsAbs(*pluginsDir) {
		*pluginsDir = filepath.Join(dir, *pluginsDir)
		cfg.PluginsDir = *pluginsDir
	}

	app.Run(*bridgeAddr, stop)
}
