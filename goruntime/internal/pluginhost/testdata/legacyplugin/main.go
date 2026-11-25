package main

import (
	"fmt"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

// Plugin is the loader entry point: implemented, enabled, event-aware.
var Plugin pluginapi.Plugin = &legacyPlugin{}

type legacyPlugin struct {
	api     *pluginapi.API
	enabled bool
}

func (p *legacyPlugin) OnEnable(api *pluginapi.API) {
	p.api = api
	p.enabled = true
	api.Log.Info("test plugin enabled")
}

func (p *legacyPlugin) OnDisable() {
	p.enabled = false
}

func (p *legacyPlugin) HandleEvent(event pluginapi.Event) bool {
	switch e := event.(type) {
	case *pluginapi.BlockBreakEvent:
		// Guard bedrock in every world.
		block := p.api.GetBlock(e.ServerKey, e.Dim, e.Pos.X, e.Pos.Y, e.Pos.Z)
		return block.Name == "minecraft:bedrock"
	case *pluginapi.PlayerDeathEvent:
		if e.Player != nil {
			p.api.Log.Info(fmt.Sprintf("%s died", e.Player.Name))
		}
		return false
	}
	return false
}
