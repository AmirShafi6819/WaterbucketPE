package world

import (
	"encoding/binary"
	"testing"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/nbt"
)

// buildPaletteNBT encodes one network-NBT compound with name/states/version.
func buildPaletteNBT(name string, states map[string]string, version int64) []byte {
	var out []byte
	out = append(out, nbt.TagCompound)
	out = appendVarint(out, 0) // root name ""
	// "name" string
	out = append(out, nbt.TagString)
	out = appendVarint(out, int64(len("name")))
	out = append(out, "name"...)
	out = appendVarint(out, int64(len(name)))
	out = append(out, name...)
	// "states" compound
	out = append(out, nbt.TagCompound)
	out = appendVarint(out, int64(len("states")))
	out = append(out, "states"...)
	if len(states) == 0 {
		out = append(out, nbt.TagEnd)
	} else {
		for k, v := range states {
			out = append(out, nbt.TagString)
			out = appendVarint(out, int64(len(k)))
			out = append(out, k...)
			out = appendVarint(out, int64(len(v)))
			out = append(out, v...)
		}
		out = append(out, nbt.TagEnd)
	}
	// "version" int
	out = append(out, nbt.TagInt)
	out = appendVarint(out, int64(len("version")))
	out = append(out, "version"...)
	var vbuf [4]byte
	binary.LittleEndian.PutUint32(vbuf[:], uint32(version))
	out = append(out, vbuf[:]...)
	out = append(out, nbt.TagEnd)
	return out
}

func appendVarint(buf []byte, v int64) []byte {
	u := uint64(v)
	for u >= 0x80 {
		buf = append(buf, byte(u)|0x80)
		u >>= 7
	}
	return append(buf, byte(u))
}

// buildSubchunkV9 packs a v9 subchunk with a persistent palette of two blocks
// (air + stone) at 4 bits per block and a known block pattern.
func buildSubchunkV9(index uint8, palette [][]byte, indices []uint16, bits uint8) []byte {
	var blob []byte
	blob = append(blob, 0x09)
	blob = append(blob, index)
	blob = append(blob, 8) // subChunkCount
	blob = append(blob, bits)
	// blockCount (present when bits < 8)
	if bits < 8 {
		var cnt [4]byte
		binary.LittleEndian.PutUint32(cnt[:], 4096)
		blob = append(blob, cnt[:]...)
	}
	// packed words
	perWord := uint32(32 / bits)
	words := (4096*int(bits) + 31) / 32
	wordBytes := make([]byte, words*4)
	for i := 0; i < 4096; i++ {
		wordIdx := uint32(i) / perWord
		shift := (uint32(i) % perWord) * uint32(bits)
		val := uint32(indices[i]) << shift
		cur := binary.LittleEndian.Uint32(wordBytes[wordIdx*4:])
		binary.LittleEndian.PutUint32(wordBytes[wordIdx*4:], cur|val)
	}
	blob = append(blob, wordBytes...)
	// palette
	blob = appendVarint(blob, int64(len(palette)))
	for _, entry := range palette {
		blob = append(blob, entry...)
	}
	return blob
}

func TestSplitBlobV9DecodesBlocks(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	stone := buildPaletteNBT("minecraft:stone", nil, 1769472)
	palette := [][]byte{air, stone}

	indices := make([]uint16, 4096)
	indices[0] = 1    // first block is stone
	indices[4095] = 1 // last block is stone
	blob := buildSubchunkV9(5, palette, indices, 4)

	subs, err := SplitBlob(blob)
	if err != nil {
		t.Fatalf("split failed: %v", err)
	}
	if len(subs) != 1 {
		t.Fatalf("want 1 subchunk, got %d", len(subs))
	}
	sub := subs[0]
	if sub.Version != 9 || sub.Index != 5 {
		t.Fatalf("header wrong: version %d index %d", sub.Version, sub.Index)
	}
	if len(sub.Persistent) != 2 || sub.Persistent[0].Name != "minecraft:air" || sub.Persistent[1].Name != "minecraft:stone" {
		t.Fatalf("palette wrong: %+v", sub.Persistent)
	}
	unpacked := sub.UnpackedIndices()
	if unpacked[0] != 1 || unpacked[4095] != 1 || unpacked[1] != 0 {
		t.Fatalf("indices wrong: %v %v %v", unpacked[0], unpacked[1], unpacked[4095])
	}
}

// TestBlockAtThroughChunk pins the full resolution chain: blob -> chunk ->
// subchunk -> block name.
func TestBlockAtThroughChunk(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	stone := buildPaletteNBT("minecraft:stone", nil, 1769472)
	planks := buildPaletteNBT("minecraft:oak_planks", map[string]string{"axis": "y"}, 1769472)
	palette := [][]byte{air, stone, planks}

	indices := make([]uint16, 4096)
	indices[0] = 1   // (0,0,0) stone
	indices[257] = 2 // y=1, z=0, x=1 -> planks
	blob := buildSubchunkV9(4, palette, indices, 4)

	reg := NewRegistry(300, 1024)
	if !reg.ApplyChunk("test-server", 0, 3, -7, blob) {
		t.Fatalf("chunk was not newly created")
	}
	worldRef := reg.World("test-server")
	if worldRef == nil {
		t.Fatal("world missing")
	}
	// chunk (3, -7) spans x 48..63, z -112..-97; subchunk index 4 = y -64+64=0..15
	block, loaded := reg.BlockQuery("test-server", 0, 48, 0, -112)
	if !loaded {
		t.Fatal("loaded chunk position reported unloaded")
	}
	if block.Name != "minecraft:stone" {
		t.Fatalf("block at (48,0,-112) = %q, want minecraft:stone", block.Name)
	}
	// index 257 -> y=1, z=0, x=1 -> world (49, 1, -112)
	plank, _ := reg.BlockQuery("test-server", 0, 49, 1, -112)
	if plank.Name != "minecraft:oak_planks" {
		t.Fatalf("block at (49,1,-112) = %q", plank.Name)
	}
	airBlock, _ := reg.BlockQuery("test-server", 0, 50, 1, -112)
	if !airBlock.IsAirBlock() {
		t.Fatalf("block at (50,1,-112) = %q, want air", airBlock.Name)
	}
	// Unloaded position reports unloaded.
	_, loaded2 := reg.BlockQuery("test-server", 0, 999, 0, 999)
	if loaded2 {
		t.Fatal("position outside chunk reported loaded")
	}
}

func TestSplitBlobV8AndV1(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	var blob []byte
	// v1 (air-only) record for index 1
	blob = append(blob, 0x01, 1, 0, 0, 0)
	// v8 record with full-air palette at bits=4
	v8 := buildSubchunkV8(paletteNBTs{entries: [][]byte{air}}, 4, make([]uint16, 4096))
	blob = append(blob, v8...)

	subs, err := SplitBlob(blob)
	if err != nil {
		t.Fatalf("split failed: %v", err)
	}
	if len(subs) != 2 {
		t.Fatalf("want 2 subchunks, got %d", len(subs))
	}
	if subs[0].Version != 1 || subs[0].BlockCount != 1 {
		t.Fatalf("v1 record wrong: %+v", subs[0])
	}
	if subs[1].Version != 8 || len(subs[1].Persistent) != 1 {
		t.Fatalf("v8 record wrong: %+v", subs[1])
	}
}

type paletteNBTs struct {
	entries [][]byte
}

func buildSubchunkV8(p paletteNBTs, bits uint8, indices []uint16) []byte {
	var blob []byte
	blob = append(blob, 0x08)
	blob = append(blob, bits)
	if bits < 8 {
		var cnt [4]byte
		binary.LittleEndian.PutUint32(cnt[:], 4096)
		blob = append(blob, cnt[:]...)
	}
	perWord := uint32(32 / bits)
	words := (4096*int(bits) + 31) / 32
	wordBytes := make([]byte, words*4)
	for i := 0; i < 4096; i++ {
		wordIdx := uint32(i) / perWord
		shift := (uint32(i) % perWord) * uint32(bits)
		val := uint32(indices[i]) << shift
		cur := binary.LittleEndian.Uint32(wordBytes[wordIdx*4:])
		binary.LittleEndian.PutUint32(wordBytes[wordIdx*4:], cur|val)
	}
	blob = append(blob, wordBytes...)
	blob = appendVarint(blob, int64(len(p.entries)))
	for _, entry := range p.entries {
		blob = append(blob, entry...)
	}
	return blob
}

func TestSplitBlobMalformedTail(t *testing.T) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	good := buildSubchunkV9(2, [][]byte{air}, make([]uint16, 4096), 4)
	blob := append(append([]byte{}, good...), 0x08, 8, 0x99) // truncated v8 record
	subs, err := SplitBlob(blob)
	if err != nil {
		t.Fatalf("malformed tail should not hard-fail: %v", err)
	}
	if len(subs) < 1 {
		t.Fatal("good prefix lost")
	}
}

func BenchmarkSplitBlob(b *testing.B) {
	air := buildPaletteNBT("minecraft:air", nil, 1769472)
	stone := buildPaletteNBT("minecraft:stone", nil, 1769472)
	var full []byte
	for i := 0; i < 16; i++ {
		indices := make([]uint16, 4096)
		indices[i] = 1
		full = append(full, buildSubchunkV9(uint8(i), [][]byte{air, stone}, indices, 4)...)
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = SplitBlob(full)
	}
}
