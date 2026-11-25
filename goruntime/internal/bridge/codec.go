package bridge

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

var (
	// ErrFrameTooLarge means a peer sent a length beyond MaxFrameBytes: the
	// stream is corrupted or hostile, so the connection must die.
	ErrFrameTooLarge = errors.New("bridge: frame length exceeds the protocol limit")
	// ErrTruncated means the payload ended before a complete field was read.
	ErrTruncated = errors.New("bridge: truncated payload")
)

// Writer appends protocol primitives to a growable buffer. All integers are
// little-endian; the byte order matches the Java side exactly.
type Writer struct {
	Buf []byte
}

func NewWriter(capacity int) *Writer {
	return &Writer{Buf: make([]byte, 0, capacity)}
}

// Reset keeps the backing array alive between frames: steady-state delta
// traffic allocates nothing but the final frame copy.
func (w *Writer) Reset() {
	w.Buf = w.Buf[:0]
}

func (w *Writer) Byte(b byte) {
	w.Buf = append(w.Buf, b)
}

func (w *Writer) Bool(v bool) {
	if v {
		w.Buf = append(w.Buf, 1)
	} else {
		w.Buf = append(w.Buf, 0)
	}
}

func (w *Writer) Int16(v int16) {
	w.Buf = binary.LittleEndian.AppendUint16(w.Buf, uint16(v))
}

func (w *Writer) Uint16(v uint16) {
	w.Buf = binary.LittleEndian.AppendUint16(w.Buf, v)
}

func (w *Writer) Int32(v int32) {
	w.Buf = binary.LittleEndian.AppendUint32(w.Buf, uint32(v))
}

func (w *Writer) Uint32(v uint32) {
	w.Buf = binary.LittleEndian.AppendUint32(w.Buf, v)
}

func (w *Writer) Int64(v int64) {
	w.Buf = binary.LittleEndian.AppendUint64(w.Buf, uint64(v))
}

func (w *Writer) Float32(v float32) {
	w.Buf = binary.LittleEndian.AppendUint32(w.Buf, math.Float32bits(v))
}

// String writes a u16-length-prefixed UTF-8 string (u16 keeps per-event frames
// small; the rare long payload uses Bytes).
func (w *Writer) String(s string) {
	l := len(s)
	if l > 65535 {
		l = 65535
	}
	w.Uint16(uint16(l))
	w.Buf = append(w.Buf, s[:l]...)
}

// Bytes writes a u32-length-prefixed blob (chunk payloads, NBT).
func (w *Writer) Bytes(b []byte) {
	w.Uint32(uint32(len(b)))
	w.Buf = append(w.Buf, b...)
}

func (w *Writer) Vec3i(x, y, z int32) {
	w.Int32(x)
	w.Int32(y)
	w.Int32(z)
}

func (w *Writer) Vec3f(x, y, z float32) {
	w.Float32(x)
	w.Float32(y)
	w.Float32(z)
}

// Item writes the shared item wire format.
func (w *Writer) Item(item Item) {
	w.String(item.Identifier)
	w.Int32(item.Damage)
	w.Int32(item.Count)
	w.Int64(item.NetID)
	if item.HasNBT && len(item.NBT) > 0 {
		w.Bool(true)
		w.Bytes(item.NBT)
	} else {
		w.Bool(false)
	}
}

// Reader walks a single frame payload. All reads are bounds-checked and fail
// with ErrTruncated instead of panicking on malformed input.
type Reader struct {
	Buf []byte
	Pos int
}

func NewReader(buf []byte) *Reader {
	return &Reader{Buf: buf}
}

func (r *Reader) Remaining() int {
	return len(r.Buf) - r.Pos
}

func (r *Reader) Byte() (byte, error) {
	if r.Remaining() < 1 {
		return 0, ErrTruncated
	}
	b := r.Buf[r.Pos]
	r.Pos++
	return b, nil
}

func (r *Reader) Bool() (bool, error) {
	b, err := r.Byte()
	if err != nil {
		return false, err
	}
	return b != 0, nil
}

func (r *Reader) Int16() (int16, error) {
	if r.Remaining() < 2 {
		return 0, ErrTruncated
	}
	v := int16(binary.LittleEndian.Uint16(r.Buf[r.Pos:]))
	r.Pos += 2
	return v, nil
}

func (r *Reader) Uint16() (uint16, error) {
	v, err := r.Int16()
	return uint16(v), err
}

func (r *Reader) Int32() (int32, error) {
	if r.Remaining() < 4 {
		return 0, ErrTruncated
	}
	v := int32(binary.LittleEndian.Uint32(r.Buf[r.Pos:]))
	r.Pos += 4
	return v, nil
}

func (r *Reader) Uint32() (uint32, error) {
	v, err := r.Int32()
	return uint32(v), err
}

func (r *Reader) Int64() (int64, error) {
	if r.Remaining() < 8 {
		return 0, ErrTruncated
	}
	v := int64(binary.LittleEndian.Uint64(r.Buf[r.Pos:]))
	r.Pos += 8
	return v, nil
}

func (r *Reader) Float32() (float32, error) {
	if r.Remaining() < 4 {
		return 0, ErrTruncated
	}
	v := math.Float32frombits(binary.LittleEndian.Uint32(r.Buf[r.Pos:]))
	r.Pos += 4
	return v, nil
}

func (r *Reader) String() (string, error) {
	l, err := r.Uint16()
	if err != nil {
		return "", err
	}
	if r.Remaining() < int(l) {
		return "", ErrTruncated
	}
	s := string(r.Buf[r.Pos : r.Pos+int(l)])
	r.Pos += int(l)
	return s, nil
}

func (r *Reader) Bytes() ([]byte, error) {
	l, err := r.Uint32()
	if err != nil {
		return nil, err
	}
	if int(l) > r.Remaining() {
		return nil, ErrTruncated
	}
	b := r.Buf[r.Pos : r.Pos+int(l)]
	r.Pos += int(l)
	return b, nil
}

func (r *Reader) Vec3i() (x, y, z int32, err error) {
	if x, err = r.Int32(); err != nil {
		return
	}
	if y, err = r.Int32(); err != nil {
		return
	}
	z, err = r.Int32()
	return
}

func (r *Reader) Vec3f() (x, y, z float32, err error) {
	if x, err = r.Float32(); err != nil {
		return
	}
	if y, err = r.Float32(); err != nil {
		return
	}
	z, err = r.Float32()
	return
}

func (r *Reader) Item() (Item, error) {
	id, err := r.String()
	if err != nil {
		return Item{}, err
	}
	damage, err := r.Int32()
	if err != nil {
		return Item{}, err
	}
	count, err := r.Int32()
	if err != nil {
		return Item{}, err
	}
	netID, err := r.Int64()
	if err != nil {
		return Item{}, err
	}
	hasNBT, err := r.Bool()
	if err != nil {
		return Item{}, err
	}
	item := Item{Identifier: id, Damage: damage, Count: count, NetID: netID, HasNBT: hasNBT}
	if hasNBT {
		item.NBT, err = r.Bytes()
		if err != nil {
			return Item{}, err
		}
	}
	return item, nil
}

// WriteFrame prefixes payload with its u32 length and flushes it.
func WriteFrame(w *bufio.Writer, payload []byte) error {
	if len(payload) > MaxFrameBytes {
		return ErrFrameTooLarge
	}
	var lenBuf [4]byte
	binary.LittleEndian.PutUint32(lenBuf[:], uint32(len(payload)))
	if _, err := w.Write(lenBuf[:]); err != nil {
		return err
	}
	if _, err := w.Write(payload); err != nil {
		return err
	}
	return w.Flush()
}

// ReadFrame reads exactly one length-prefixed frame. The returned slice is
// only valid until the next call.
func ReadFrame(r *bufio.Reader, scratch []byte) ([]byte, []byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, scratch, err
	}
	length := binary.LittleEndian.Uint32(lenBuf[:])
	if length > MaxFrameBytes {
		return nil, scratch, ErrFrameTooLarge
	}
	if cap(scratch) < int(length) {
		grow := make([]byte, nextPow2(int(length)))
		scratch = grow
	}
	buf := scratch[:length]
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, scratch, err
	}
	return buf, scratch, nil
}

func nextPow2(v int) int {
	p := 4096
	for p < v {
		p *= 2
	}
	return p
}

// EncodeHello builds the Java -> Go handshake.
func EncodeHello(w *Writer, proxyVersion string, worldView bool, retentionSec, maxChunks, syncTimeoutMs uint32) {
	w.Byte(OpHello)
	w.Byte(ProtocolVersion)
	w.String(proxyVersion)
	w.Bool(worldView)
	w.Uint32(retentionSec)
	w.Uint32(maxChunks)
	w.Uint32(syncTimeoutMs)
}

// HelloAck is the parsed Go -> Java handshake.
type HelloAck struct {
	GoVersion  string
	APIVersion string
	Plugins    []string
}

// DecodeHelloAck parses an OpHelloAck payload (opcode already stripped).
func DecodeHelloAck(r *Reader) (*HelloAck, error) {
	proto, err := r.Byte()
	if err != nil {
		return nil, err
	}
	if proto != ProtocolVersion {
		return nil, fmt.Errorf("bridge: sidecar speaks protocol %d, proxy expects %d", proto, ProtocolVersion)
	}
	ack := &HelloAck{}
	if ack.GoVersion, err = r.String(); err != nil {
		return nil, err
	}
	if ack.APIVersion, err = r.String(); err != nil {
		return nil, err
	}
	count, err := r.Uint16()
	if err != nil {
		return nil, err
	}
	ack.Plugins = make([]string, 0, count)
	for i := 0; i < int(count); i++ {
		name, err := r.String()
		if err != nil {
			return nil, err
		}
		ack.Plugins = append(ack.Plugins, name)
	}
	return ack, nil
}

// EncodeHelloAck builds the Go -> Java handshake.
func EncodeHelloAck(w *Writer, goVersion, apiVersion string, plugins []string) {
	w.Byte(OpHelloAck)
	w.Byte(ProtocolVersion)
	w.String(goVersion)
	w.String(apiVersion)
	w.Uint16(uint16(len(plugins)))
	for _, p := range plugins {
		w.String(p)
	}
}

// EncodePluginStatus builds the OpPluginStatus frame body.
func EncodePluginStatus(w *Writer, statuses []PluginStatusEntry) {
	w.Byte(OpPluginStatus)
	w.Uint16(uint16(len(statuses)))
	for _, s := range statuses {
		w.String(s.Name)
		w.String(s.Version)
		w.String(s.APIVersion)
		w.Byte(s.State)
		w.String(s.Error)
	}
}

// PluginStatusEntry is one row of the Go plugin list.
type PluginStatusEntry struct {
	Name       string
	Version    string
	APIVersion string
	State      byte
	Error      string
}

// EncodeEvent builds an event frame (sync or async) from positional fields.
func EncodeEvent(w *Writer, opcode byte, callID uint32, eventID uint16, fields []Field) {
	w.Byte(opcode)
	if opcode == OpEventSync {
		w.Uint32(callID)
	}
	w.Uint16(eventID)
	for _, f := range fields {
		w.Field(f)
	}
}

// Field is one typed value inside an event or query payload.
type Field struct {
	Type       FieldType
	I32        int32
	I64        int64
	F32        float32
	Bool       bool
	Str        string
	Xi, Yi, Zi int32
	Xf, Yf, Zf float32
	ItemVal    Item
	Bytes      []byte
}

// FieldType discriminates Field values.
type FieldType uint8

const (
	TypeNone  FieldType = 0
	TypeI32   FieldType = 1
	TypeI64   FieldType = 2
	TypeF32   FieldType = 3
	TypeBool  FieldType = 4
	TypeStr   FieldType = 5
	TypeVec3i FieldType = 6
	TypeVec3f FieldType = 7
	TypeItem  FieldType = 8
	TypeBytes FieldType = 9
)

// Vec3iVal packs integer coordinates into a Field (legacy helper).
type Vec3iVal struct{ X, Y, Z int32 }

// Vec3fVal packs float coordinates into a Field (legacy helper).
type Vec3fVal struct{ X, Y, Z float32 }

// Field writes one typed field.
func (w *Writer) Field(f Field) {
	w.Byte(byte(f.Type))
	switch f.Type {
	case TypeI32:
		w.Int32(f.I32)
	case TypeI64:
		w.Int64(f.I64)
	case TypeF32:
		w.Float32(f.F32)
	case TypeBool:
		w.Bool(f.Bool)
	case TypeStr:
		w.String(f.Str)
	case TypeVec3i:
		w.Vec3i(f.Xi, f.Yi, f.Zi)
	case TypeVec3f:
		w.Vec3f(f.Xf, f.Yf, f.Zf)
	case TypeItem:
		w.Item(f.ItemVal)
	case TypeBytes:
		w.Bytes(f.Bytes)
	}
}

// Field reads one typed field.
func (r *Reader) Field() (Field, error) {
	t, err := r.Byte()
	if err != nil {
		return Field{}, err
	}
	f := Field{Type: FieldType(t)}
	switch FieldType(t) {
	case TypeI32:
		f.I32, err = r.Int32()
	case TypeI64:
		f.I64, err = r.Int64()
	case TypeF32:
		f.F32, err = r.Float32()
	case TypeBool:
		f.Bool, err = r.Bool()
	case TypeStr:
		f.Str, err = r.String()
	case TypeVec3i:
		f.Xi, f.Yi, f.Zi, err = r.Vec3i()
	case TypeVec3f:
		f.Xf, f.Yf, f.Zf, err = r.Vec3f()
	case TypeItem:
		f.ItemVal, err = r.Item()
	case TypeBytes:
		f.Bytes, err = r.Bytes()
	}
	if err != nil {
		return Field{}, err
	}
	return f, nil
}

// Fields reads n typed fields.
func (r *Reader) Fields(n int) ([]Field, error) {
	fields := make([]Field, 0, n)
	for i := 0; i < n; i++ {
		f, err := r.Field()
		if err != nil {
			return nil, err
		}
		fields = append(fields, f)
	}
	return fields, nil
}
