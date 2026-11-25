// WorldGuard example: protects bedrock on every downstream server. Cancelling
// BlockBreakEvent drops the break action before the downstream server sees it,
// so the block really survives.
package main

import (
	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

var Plugin pluginapi.Plugin = &worldGuard{}

type worldGuard struct {
	api     *pluginapi.API
	blocked int
}

func (p *worldGuard) OnEnable(api *pluginapi.API) {
	p.api = api
	api.Log.Info("WorldGuard ready: bedrock is protected")
}

func (p *worldGuard) OnDisable() {
	p.api.Log.Info("WorldGuard disabled")
}

func (p *worldGuard) HandleEvent(event pluginapi.Event) bool {
	switch e := event.(type) {
	case *pluginapi.BlockBreakEvent:
		block := p.api.GetBlock(e.ServerKey, e.Dim, e.Pos.X, e.Pos.Y, e.Pos.Z)
		if block.Name == "minecraft:bedrock" {
			p.blocked++
			if e.Player != nil {
				_ = e.Player.SendMessage(p.api, "§cBedrock is protected! (blocked "+itoa(p.blocked)+" attempts)")
			}
			return true
		}
	case *pluginapi.PlayerDeathEvent:
		if e.Player != nil {
			_ = e.Player.SendMessage(p.api, "§7See you after respawn.")
		}
	}
	return false
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}
