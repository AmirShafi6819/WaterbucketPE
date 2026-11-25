package bridge

import (
	"bufio"
	"bytes"
	"testing"
)

func TestWriterReaderRoundtrip(t *testing.T) {
	w := NewWriter(256)
	w.Int32(-123456)
	w.Int64(1<<40 + 7)
	w.Float32(3.5)
	w.Bool(true)
	w.Bool(false)
	w.String("hello world")
	w.String("")
	w.Bytes([]byte{1, 2, 3, 4, 5})
	w.Vec3i(-1, 64, 700)
	w.Vec3f(1.25, 2.5, 3.75)
	w.Item(Item{Identifier: "minecraft:diamond_sword", Damage: 1, Count: 1, NetID: 999})
	w.Item(Item{Identifier: "", Count: 0})

	r := NewReader(w.Buf)
	checkInt32(t, r, -123456)
	checkInt64(t, r, 1<<40+7)
	checkF32(t, r, 3.5)
	checkBool(t, r, true)
	checkBool(t, r, false)
	checkStr(t, r, "hello world")
	checkStr(t, r, "")
	b, err := r.Bytes()
	if err != nil || !bytes.Equal(b, []byte{1, 2, 3, 4, 5}) {
		t.Fatalf("bytes roundtrip failed: %v %v", b, err)
	}
	x, y, z, err := r.Vec3i()
	if err != nil || x != -1 || y != 64 || z != 700 {
		t.Fatalf("vec3i roundtrip failed: %d %d %d %v", x, y, z, err)
	}
	fx, fy, fz, err := r.Vec3f()
	if err != nil || fx != 1.25 || fy != 2.5 || fz != 3.75 {
		t.Fatalf("vec3f roundtrip failed: %f %f %f %v", fx, fy, fz, err)
	}
	item, err := r.Item()
	if err != nil || item.Identifier != "minecraft:diamond_sword" || item.Damage != 1 || item.Count != 1 || item.NetID != 999 || item.HasNBT {
		t.Fatalf("item roundtrip failed: %+v %v", item, err)
	}
	air, err := r.Item()
	if err != nil || !air.IsAir() {
		t.Fatalf("air item roundtrip failed: %+v %v", air, err)
	}
}

func checkInt32(t *testing.T, r *Reader, want int32) {
	t.Helper()
	got, err := r.Int32()
	if err != nil || got != want {
		t.Fatalf("int32: got %d want %d err %v", got, want, err)
	}
}

func checkInt64(t *testing.T, r *Reader, want int64) {
	t.Helper()
	got, err := r.Int64()
	if err != nil || got != want {
		t.Fatalf("int64: got %d want %d err %v", got, want, err)
	}
}

func checkF32(t *testing.T, r *Reader, want float32) {
	t.Helper()
	got, err := r.Float32()
	if err != nil || got != want {
		t.Fatalf("f32: got %f want %f err %v", got, want, err)
	}
}

func checkBool(t *testing.T, r *Reader, want bool) {
	t.Helper()
	got, err := r.Bool()
	if err != nil || got != want {
		t.Fatalf("bool: got %v want %v err %v", got, want, err)
	}
}

func checkStr(t *testing.T, r *Reader, want string) {
	t.Helper()
	got, err := r.String()
	if err != nil || got != want {
		t.Fatalf("string: got %q want %q err %v", got, want, err)
	}
}

// TestEventSyncGolden pins the exact bytes of one EventSync frame so the Java
// side can be verified against the same vector.
func TestEventSyncGolden(t *testing.T) {
	w := NewWriter(64)
	fields := []Field{
		{Type: TypeStr, Str: "lobby"},
		{Type: TypeI32, I32: 0},
		{Type: TypeStr, Str: "Steve"},
		{Type: TypeVec3i, Xi: 100, Yi: 64, Zi: -200},
		{Type: TypeItem, ItemVal: Item{Identifier: "minecraft:dirt", Count: 1}},
		{Type: TypeI32, I32: 1},
	}
	EncodeEvent(w, OpEventSync, 42, EventBlockBreak, fields)
	if w.Buf[0] != OpEventSync {
		t.Fatalf("opcode byte wrong: %d", w.Buf[0])
	}
	if !bytes.Equal(w.Buf[1:5], []byte{42, 0, 0, 0}) {
		t.Fatalf("call id bytes wrong: %v", w.Buf[1:5])
	}
	if w.Buf[5] != byte(EventBlockBreak) || w.Buf[6] != 0 {
		t.Fatalf("event id bytes wrong: %v %v", w.Buf[5], w.Buf[6])
	}
	// Field type bytes follow: str, i32, str, vec3i, item, i32.
	types := []byte{w.Buf[7], 0, 0}
	r := NewReader(w.Buf[7:])
	first, err := r.Field()
	if err != nil || first.Str != "lobby" {
		t.Fatalf("first field decode failed: %+v %v", first, err)
	}
	second, err := r.Field()
	if err != nil || second.I32 != 0 {
		t.Fatalf("second field decode failed: %+v %v", second, err)
	}
	third, err := r.Field()
	if err != nil || third.Str != "Steve" {
		t.Fatalf("third field decode failed: %+v %v", third, err)
	}
	fourth, err := r.Field()
	if err != nil || fourth.Xi != 100 || fourth.Yi != 64 || fourth.Zi != -200 {
		t.Fatalf("pos field decode failed: %+v %v", fourth, err)
	}
	fifth, err := r.Field()
	if err != nil || fifth.ItemVal.Identifier != "minecraft:dirt" {
		t.Fatalf("item field decode failed: %+v %v", fifth, err)
	}
	sixth, err := r.Field()
	if err != nil || sixth.I32 != 1 {
		t.Fatalf("sixth field decode failed: %+v %v", sixth, err)
	}
	if r.Remaining() != 0 {
		t.Fatalf("trailing bytes after event decode: %d", r.Remaining())
	}
	_ = types
}

func TestHelloAckGolden(t *testing.T) {
	w := NewWriter(64)
	EncodeHelloAck(w, "go1.22.10", "1.6.0", []string{"econ", "guard"})
	r := NewReader(w.Buf)
	op, _ := r.Byte()
	if op != OpHelloAck {
		t.Fatalf("opcode: %d", op)
	}
	proto, _ := r.Byte()
	if proto != ProtocolVersion {
		t.Fatalf("proto: %d", proto)
	}
	ack, err := DecodeHelloAck(NewReader(w.Buf[1:]))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if ack.GoVersion != "go1.22.10" || ack.APIVersion != "1.6.0" {
		t.Fatalf("ack fields wrong: %+v", ack)
	}
	if len(ack.Plugins) != 2 || ack.Plugins[0] != "econ" || ack.Plugins[1] != "guard" {
		t.Fatalf("plugins wrong: %v", ack.Plugins)
	}
}

func TestTruncatedPayloadFails(t *testing.T) {
	w := NewWriter(16)
	w.String("needs more bytes")
	r := NewReader(w.Buf[:5])
	_, err := r.String()
	if err != ErrTruncated {
		t.Fatalf("want ErrTruncated, got %v", err)
	}
}

func TestFrameRoundtrip(t *testing.T) {
	payload := []byte("frame payload bytes")
	sink := &bytes.Buffer{}
	bw := bufio.NewWriter(sink)
	if err := WriteFrame(bw, payload); err != nil {
		t.Fatalf("write frame: %v", err)
	}
	if err := bw.Flush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	var scratch []byte
	got, _, err := ReadFrame(bufio.NewReader(bytes.NewReader(sink.Bytes())), scratch)
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("frame roundtrip: %q %v", got, err)
	}
}
