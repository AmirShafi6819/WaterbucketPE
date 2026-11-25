// InventoryWatch example: every time a player switches held items, the plugin
// reports the item; on join it shows how many slots of the main inventory the
// model already observed. Demonstrates the item/inventory part of the API.
package main

import (
	"fmt"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"
)

var Plugin pluginapi.Plugin = &watcher{}

type watcher struct {
	api *pluginapi.API
}

func (p *watcher) OnEnable(api *pluginapi.API) {
	p.api = api
	api.Log.Info("InventoryWatch ready")
}

func (p *watcher) OnDisable() {}

func (p *watcher) HandleEvent(event pluginapi.Event) bool {
	switch e := event.(type) {
	case *pluginapi.PlayerItemHeldEvent:
		if e.Player == nil {
			return false
		}
		if !e.Item.IsAir() {
			name := e.Item.Name()
			if custom := e.Item.CustomName(); custom != "" {
				name += " (" + custom + ")"
			}
			_ = e.Player.SendMessage(p.api, fmt.Sprintf("§7Now holding: §f%s §7x%d", name, e.Item.Count))
		}
	case *pluginapi.InventoryUpdateEvent:
		// React to chest/hotbar changes for anyone tracking containers.
		if e.Player != nil && e.WindowID == 12 && e.Item.IsAir() {
			// Slot emptied: the model already reflects it for other queries.
			_ = e.Player.SendTip(p.api, "slot emptied")
		}
	}
	return false
}
