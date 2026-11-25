package world

import (
	"encoding/binary"
	"errors"
	"strconv"

	"github.com/AmirShafi6819/WaterbucketPE/goruntime/internal/nbt"
)

// The serialized chunk blob the proxy forwards verbatim is a concatenation of
// subchunk records. Three generations exist in the wild:
//
//	v9: [0x09][index u8][subChunkCount u8][data]  (1.18+, absolute y index)
//	v8: [0x08][data]                              (1.18+, no y header)
//	v1: [0x01][blockCount u32 LE]                 (empty/legacy air-only)
//
// data (v8/v9) carries a palette + packed indices:
//
//	[bitsPerBlock u8]
//	[blockCount u32 LE]              only when bitsPerBlock < 8
//	[words u32 LE x ceil(4096*bits/32)]
//	[paletteLen varint]
//	[palette entries]                persistent = NBT compounds;
//	                                 runtime = u32 varint ids (global table)
var ErrMalformedChunk = errors.New("world: malformed chunk payload")

const (
	subchunkBlocks = 4096 // 16 * 16 * 16
	maxSubchunks   = 24   // y -64..320
)

// PaletteEntry is one palette slot: the resolved block identity.
type PaletteEntry struct {
	Name   string
	States map[string]string
}

// IsAirBlock reports whether this palette entry is the air block.
func (p PaletteEntry) IsAirBlock() bool {
	return p.Name == "minecraft:air" || p.Name == "minecraft:air<" || p.Name == ""
}

// Subchunk stores the packed representation of one 16x16x16 cube. Indices
// stay packed: decoding 4096 indices per subchunk on ingest would burn CPU for
// blocks nobody asks about; unpacking happens lazily per query.
type Subchunk struct {
	Version    uint8
	Index      uint8
	Bits       uint8
	Words      []uint32
	BlockCount int32
	// Persistent holds NBT palette entries when the subchunk carries them.
	Persistent []PaletteEntry
	// Runtime holds raw palette ids when the subchunk uses the global table.
	Runtime []int32
}

// UnpackedIndices decodes the packed word array into 4096 palette indexes.
func (s *Subchunk) UnpackedIndices() []uint16 {
	out := make([]uint16, subchunkBlocks)
	if s.Bits == 0 {
		return out
	}
	bits := s.Bits
	if bits < 4 {
		bits = 4
	}
	perWord := uint32(32 / bits)
	mask := uint32(1)<<bits - 1
	for i := 0; i < subchunkBlocks; i++ {
		word := s.Words[uint32(i)/perWord]
		out[i] = uint16((word >> ((uint32(i) % perWord) * uint32(bits))) & mask)
	}
	return out
}

// SplitBlob walks a serialized chunk payload and returns its subchunks.
// Malformed tails are ignored (whatever parsed before stays usable): servers
// occasionally truncate, and dropping one subchunk beats losing the chunk.
func SplitBlob(blob []byte) ([]*Subchunk, error) {
	var subs []*Subchunk
	pos := 0
	for pos < len(blob) {
		version := blob[pos]
		pos++
		switch version {
		case 9:
			if pos+2 > len(blob) {
				return subs, nil
			}
			index := blob[pos]
			_ = blob[pos+1] // subChunkCount: informational only
			pos += 2
			sub, next, err := decodeSubchunkData(blob, pos, version)
			if err != nil {
				return subs, nil
			}
			sub.Index = index
			subs = append(subs, sub)
			pos = next
		case 8:
			sub, next, err := decodeSubchunkData(blob, pos, version)
			if err != nil {
				return subs, nil
			}
			subs = append(subs, sub)
			pos = next
		case 1:
			if pos+4 > len(blob) {
				return subs, nil
			}
			count := int32(binary.LittleEndian.Uint32(blob[pos:]))
			pos += 4
			subs = append(subs, &Subchunk{Version: 1, BlockCount: count, Bits: 0})
		default:
			// Unknown generation byte: the rest is unparseable, stop here.
			return subs, nil
		}
	}
	return subs, nil
}

// decodeSubchunkData parses one v8/v9 body starting at start; returns the
// subchunk and the offset of the next record.
func decodeSubchunkData(blob []byte, start int, version uint8) (*Subchunk, int, error) {
	sub := &Subchunk{Version: version}
	if start >= len(blob) {
		return sub, start, ErrMalformedChunk
	}
	bits := blob[start]
	start++
	if bits < 4 {
		bits = 4
	}
	sub.Bits = bits

	if bits < 8 {
		// Explicit block count trims the padding of the last packed word.
		if start+4 > len(blob) {
			return sub, start, ErrMalformedChunk
		}
		sub.BlockCount = int32(binary.LittleEndian.Uint32(blob[start:]))
		start += 4
	}

	words := (subchunkBlocks*int(bits) + 31) / 32
	if start+words*4 > len(blob) {
		return sub, start, ErrMalformedChunk
	}
	sub.Words = make([]uint32, words)
	for i := 0; i < words; i++ {
		sub.Words[i] = binary.LittleEndian.Uint32(blob[start+i*4:])
	}
	start += words * 4

	// Palette: varint count then entries.
	count, advance := readVarint(blob, start)
	if advance < 0 || count < 0 || count > 65535 {
		return sub, start, ErrMalformedChunk
	}
	start += advance

	if bits < 8 {
		// Persistent palette: count NBT compound entries.
		persistent := make([]PaletteEntry, 0, count)
		for i := int64(0); i < count && start < len(blob); i++ {
			reader := nbt.NewReader(blob[start:])
			root, err := reader.ReadRoot()
			if err != nil {
				// A broken palette entry poisons the rest: keep the chunk
				// with whatever resolved so far.
				return sub, start, ErrMalformedChunk
			}
			start += reader.Position()
			persistent = append(persistent, paletteFromCompound(root))
		}
		sub.Persistent = persistent
	} else {
		// Runtime palette: raw global ids, resolved through the world's
		// block property table when one arrived in WORLD_META.
		runtime := make([]int32, 0, count)
		for i := int64(0); i < count; i++ {
			v, advance := readVarint(blob, start)
			if advance < 0 {
				return sub, start, ErrMalformedChunk
			}
			start += advance
			runtime = append(runtime, int32(v))
		}
		sub.Runtime = runtime
	}
	return sub, start, nil
}

// paletteFromCompound flattens an NBT palette entry: name plus its states
// compound (state values render as their string form).
func paletteFromCompound(root nbt.Compound) PaletteEntry {
	entry := PaletteEntry{Name: root.GetString("name"), States: map[string]string{}}
	states := root.GetCompound("states")
	for key, value := range states {
		switch v := value.(type) {
		case int64:
			entry.States[key] = formatBoolState(key, v)
		case float64:
			entry.States[key] = trimFloat(v)
		case string:
			entry.States[key] = v
		}
	}
	return entry
}

func formatBoolState(key string, v int64) string {
	if v == 0 {
		return "false"
	}
	return "true"
}

func trimFloat(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

func ftoa(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}

func readVarint(buf []byte, pos int) (int64, int) {
	var result uint64
	start := pos
	for shift := uint(0); shift < 35; shift += 7 {
		if pos >= len(buf) {
			return 0, -1
		}
		b := buf[pos]
		pos++
		result |= uint64(b&0x7F) << shift
		if b&0x80 == 0 {
			return int64(result), pos - start
		}
	}
	return 0, -1
}

// BlockAt resolves one world-space block inside a chunk's subchunk.
// (lx, y, lz) are chunk-local: lx/lz 0..15, y 0..15 inside the subchunk.
func (c *Chunk) BlockAt(dim *Dimension, sub *Subchunk, lx, y, lz int, blockTable map[int32]PaletteEntry) PaletteEntry {
	if sub == nil {
		return PaletteEntry{Name: "minecraft:air"}
	}
	if sub.Bits == 0 {
		// Version 1 records are pure air.
		return PaletteEntry{Name: "minecraft:air"}
	}
	indices := sub.UnpackedIndices()
	idx := (y*16+lz)*16 + lx
	if idx < 0 || idx >= subchunkBlocks {
		return PaletteEntry{Name: "minecraft:air"}
	}
	paletteIndex := int(indices[idx])
	if len(sub.Persistent) > 0 {
		if paletteIndex < len(sub.Persistent) {
			return sub.Persistent[paletteIndex]
		}
		return PaletteEntry{Name: "minecraft:air"}
	}
	if len(sub.Runtime) > 0 {
		if paletteIndex < len(sub.Runtime) {
			if blockTable != nil {
				if entry, ok := blockTable[sub.Runtime[paletteIndex]]; ok {
					return entry
				}
			}
			return PaletteEntry{Name: "minecraft:runtime_" + itoa(int64(sub.Runtime[paletteIndex]))}
		}
	}
	return PaletteEntry{Name: "minecraft:air"}
}
