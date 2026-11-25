package main

import "github.com/AmirShafi6819/WaterbucketPE/goruntime/pluginapi"

var Plugin pluginapi.Plugin = &badPlugin{}

type badPlugin struct{}

func (p *badPlugin) OnEnable(api *pluginapi.API) {}
func (p *badPlugin) OnDisable()                  {}
func (p *badPlugin) HandleEvent(event pluginapi.Event) bool {
	return false
}
