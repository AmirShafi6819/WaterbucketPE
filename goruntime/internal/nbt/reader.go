// Package nbt implements a minimal reader for the Bedrock network NBT dialect
// (little-endian, varint-prefixed strings and array lengths). It exists to
// decode subchunk palettes and block entities arriving from the proxy without
// pulling a full NBT dependency; writers are unnecessary because the Go
// runtime never produces NBT itself.
package nbt

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

// Network tag ids.
const (
	TagEnd       = 0
	TagByte      = 1
	TagShort     = 2
	TagInt       = 3
	TagLong      = 4
	TagFloat     = 5
	TagDouble    = 6
	TagByteArray = 7
	TagString    = 8
	TagList      = 9
	TagCompound  = 10
	TagIntArray  = 11
)

var ErrMalformed = errors.New("nbt: malformed payload")

// Value is one decoded tag value: int64/float64/string/bool/[]byte/[]int32/[]Value/map[string]Value.
type Value interface{}

// Compound is a named collection of values.
type Compound map[string]Value

// GetBool treats a byte tag as a boolean.
func (c Compound) GetBool(key string) bool {
	v, ok := c[key]
	if !ok {
		return false
	}
	b, ok := v.(int64)
	return ok && b != 0
}

// GetString returns a string tag or "".
func (c Compound) GetString(key string) string {
	if s, ok := c[key].(string); ok {
		return s
	}
	return ""
}

// GetInt returns an integer tag or 0.
func (c Compound) GetInt(key string) int64 {
	if v, ok := c[key].(int64); ok {
		return v
	}
	return 0
}

// GetFloat returns a float tag or 0.
func (c Compound) GetFloat(key string) float64 {
	if v, ok := c[key].(float64); ok {
		return v
	}
	if v, ok := c[key].(int64); ok {
		return float64(v)
	}
	return 0
}

// GetCompound returns a nested compound or nil.
func (c Compound) GetCompound(key string) Compound {
	if v, ok := c[key].(Compound); ok {
		return v
	}
	return nil
}

// Reader decodes one root compound from a payload.
type Reader struct {
	buf []byte
	pos int
}

// Position reports how many bytes the last parse consumed.
func (r *Reader) Position() int {
	return r.pos
}

// NewReader wraps an NBT payload.
func NewReader(buf []byte) *Reader {
	return &Reader{buf: buf}
}

// ReadRoot parses a single compound starting at a string name header, the
// shape Bedrock uses for palettes and block entities.
func (r *Reader) ReadRoot() (Compound, error) {
	tag, err := r.readByte()
	if err != nil {
		return nil, err
	}
	if tag != TagCompound {
		return nil, fmt.Errorf("nbt: root tag is %d, want compound", tag)
	}
	if _, err = r.readString(); err != nil {
		return nil, err
	}
	return r.readCompoundBody()
}

func (r *Reader) remaining() int {
	return len(r.buf) - r.pos
}

func (r *Reader) need(n int) error {
	if n > r.remaining() {
		return ErrMalformed
	}
	return nil
}

func (r *Reader) readByte() (byte, error) {
	if err := r.need(1); err != nil {
		return 0, err
	}
	b := r.buf[r.pos]
	r.pos++
	return b, nil
}

func (r *Reader) readUint16() (uint16, error) {
	if err := r.need(2); err != nil {
		return 0, err
	}
	v := binary.LittleEndian.Uint16(r.buf[r.pos:])
	r.pos += 2
	return v, nil
}

func (r *Reader) readInt32() (int32, error) {
	if err := r.need(4); err != nil {
		return 0, err
	}
	v := int32(binary.LittleEndian.Uint32(r.buf[r.pos:]))
	r.pos += 4
	return v, nil
}

func (r *Reader) readInt64() (int64, error) {
	if err := r.need(8); err != nil {
		return 0, err
	}
	v := int64(binary.LittleEndian.Uint64(r.buf[r.pos:]))
	r.pos += 8
	return v, nil
}

func (r *Reader) readFloat32() (float64, error) {
	if err := r.need(4); err != nil {
		return 0, err
	}
	v := math.Float32frombits(binary.LittleEndian.Uint32(r.buf[r.pos:]))
	r.pos += 4
	return float64(v), nil
}

func (r *Reader) readFloat64() (float64, error) {
	if err := r.need(8); err != nil {
		return 0, err
	}
	v := math.Float64frombits(binary.LittleEndian.Uint64(r.buf[r.pos:]))
	r.pos += 8
	return v, nil
}

// readString reads a varint length followed by UTF-8 bytes: the network
// variant of Bedrock NBT.
func (r *Reader) readString() (string, error) {
	l, err := r.readVarint()
	if err != nil {
		return "", err
	}
	if l < 0 || l > int64(r.remaining()) {
		return "", ErrMalformed
	}
	s := string(r.buf[r.pos : r.pos+int(l)])
	r.pos += int(l)
	return s, nil
}

func (r *Reader) readVarint() (int64, error) {
	var result uint64
	for shift := uint(0); shift < 35; shift += 7 {
		b, err := r.readByte()
		if err != nil {
			return 0, err
		}
		result |= uint64(b&0x7F) << shift
		if b&0x80 == 0 {
			return int64(result), nil
		}
	}
	return 0, ErrMalformed
}

func (r *Reader) readCompoundBody() (Compound, error) {
	c := Compound{}
	for {
		tag, err := r.readByte()
		if err != nil {
			return nil, err
		}
		if tag == TagEnd {
			return c, nil
		}
		name, err := r.readString()
		if err != nil {
			return nil, err
		}
		value, err := r.readTagValue(tag)
		if err != nil {
			return nil, err
		}
		c[name] = value
	}
}

func (r *Reader) readTagValue(tag byte) (Value, error) {
	switch tag {
	case TagByte:
		b, err := r.readByte()
		return int64(b), err
	case TagShort:
		v, err := r.readUint16()
		return int64(int16(v)), err
	case TagInt:
		v, err := r.readInt32()
		return int64(v), err
	case TagLong:
		return r.readInt64()
	case TagFloat:
		return r.readFloat32()
	case TagDouble:
		return r.readFloat64()
	case TagByteArray:
		l, err := r.readVarint()
		if err != nil {
			return nil, err
		}
		if l < 0 || l > int64(r.remaining()) {
			return nil, ErrMalformed
		}
		out := make([]byte, l)
		copy(out, r.buf[r.pos:r.pos+int(l)])
		r.pos += int(l)
		return out, nil
	case TagString:
		return r.readString()
	case TagList:
		listTag, err := r.readByte()
		if err != nil {
			return nil, err
		}
		l, err := r.readVarint()
		if err != nil {
			return nil, err
		}
		if l < 0 || l > int64(len(r.buf)) {
			return nil, ErrMalformed
		}
		list := make([]Value, 0, min(l, 1024))
		for i := int64(0); i < l; i++ {
			var v Value
			if listTag == TagCompound {
				// Network NBT lists of compounds repeat no name header: each
				// entry is a bare compound body.
				v, err = r.readCompoundBody()
			} else {
				v, err = r.readTagValue(listTag)
			}
			if err != nil {
				return nil, err
			}
			list = append(list, v)
		}
		return list, nil
	case TagCompound:
		return r.readCompoundBody()
	case TagIntArray:
		l, err := r.readVarint()
		if err != nil {
			return nil, err
		}
		if l < 0 || l > int64(r.remaining()/4) {
			return nil, ErrMalformed
		}
		out := make([]int32, l)
		for i := range out {
			v, err := r.readInt32()
			if err != nil {
				return nil, err
			}
			out[i] = v
		}
		return out, nil
	default:
		return nil, fmt.Errorf("nbt: unknown tag %d", tag)
	}
}

func min(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

// Ensure io is used (kept for future stream support).
var _ = io.EOF
