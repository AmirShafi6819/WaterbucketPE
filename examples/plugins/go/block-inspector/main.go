// BlockInspector example: when a player right-clicks a block, this plugin
// reads the real block (plus any sign text) from the live world model and
// tells the player what they are looking at.
package main

import (
	"fmt"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

var Plugin pluginapi.Plugin = &inspector{}

type inspector struct {
	api *pluginapi.API
}

func (p *inspector) OnEnable(api *pluginapi.API) {
	p.api = api
	api.Log.Info("BlockInspector ready")
}

func (p *inspector) OnDisable() {}

func (p *inspector) HandleEvent(event pluginapi.Event) bool {
	switch e := event.(type) {
	case *pluginapi.PlayerInteractEvent:
		if e.Action != pluginapi.RightClickBlock || e.Player == nil {
			return false
		}
		block := p.api.GetBlock(e.ServerKey, e.Dim, e.Pos.X, e.Pos.Y, e.Pos.Z)
		message := fmt.Sprintf("§7Block: §f%s", block.Name)
		if be := p.api.GetBlockEntity(e.ServerKey, e.Dim, e.Pos.X, e.Pos.Y, e.Pos.Z); be != nil {
			if lines := be.SignText(); len(lines) > 0 {
				message += " §8| §7sign: §f" + lines[0]
			} else {
				message += " §8(" + be.Type + ")"
			}
		}
		_ = e.Player.SendMessage(p.api, message)
	}
	return false
}
