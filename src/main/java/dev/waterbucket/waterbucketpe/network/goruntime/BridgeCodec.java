/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.network.goruntime;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java mirror of the Go bridge protocol (goruntime/internal/bridge): one
 * u32 little-endian frame length, one opcode byte, then typed fields. Both
 * sides have conformance tests pinning the same golden vectors, so the wire
 * format cannot drift.
 */
public final class BridgeCodec {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 32 << 20;

    public static final byte OP_HELLO = 0x01;
    public static final byte OP_HELLO_ACK = 0x02;
    public static final byte OP_PING = 0x0F;
    public static final byte OP_PONG = 0x10;
    public static final byte OP_DELTA = 0x20;
    public static final byte OP_EVENT_SYNC = 0x30;
    public static final byte OP_EVENT_RESULT = 0x31;
    public static final byte OP_EVENT_ASYNC = 0x32;
    public static final byte OP_QUERY = 0x40;
    public static final byte OP_QUERY_RESULT = 0x41;
    public static final byte OP_RPC_CALL = 0x50;
    public static final byte OP_RPC_RESULT = 0x51;
    public static final byte OP_LOG = 0x60;
    public static final byte OP_PLUGIN_STATUS = 0x70;
    public static final byte OP_SHUTDOWN = 0x7F;

    public static final byte QUERY_OK = 0;
    public static final byte QUERY_NOT_FOUND = 1;
    public static final byte QUERY_UNAVAILABLE = 2;

    public static final byte PLUGIN_DISABLED = 0;
    public static final byte PLUGIN_ENABLED = 1;
    public static final byte PLUGIN_FAILED = 2;

    // Delta types (world observations into the Go model).
    public static final byte DELTA_WORLD_META = 1;
    public static final byte DELTA_CHUNK = 2;
    public static final byte DELTA_BLOCK_UPDATE = 3;
    public static final byte DELTA_BLOCK_ENTITY = 4;
    public static final byte DELTA_ENTITY_ADD = 5;
    public static final byte DELTA_ENTITY_REMOVE = 6;
    public static final byte DELTA_ENTITY_MOVE = 7;
    public static final byte DELTA_ENTITY_MOTION = 8;
    public static final byte DELTA_ENTITY_META = 9;
    public static final byte DELTA_ENTITY_ATTRIBUTE = 10;
    public static final byte DELTA_ENTITY_EFFECT = 11;
    public static final byte DELTA_PLAYER_INFO = 12;
    public static final byte DELTA_PLAYER_REMOVE = 13;
    public static final byte DELTA_PLAYER_EQUIP = 14;
    public static final byte DELTA_CONTAINER = 15;
    public static final byte DELTA_INVENTORY_CONTENT = 16;
    public static final byte DELTA_INVENTORY_SLOT = 17;
    public static final byte DELTA_WORLD_TIME = 18;
    public static final byte DELTA_WORLD_DIFFICULTY = 19;
    public static final byte DELTA_WORLD_GAMERULE = 20;
    public static final byte DELTA_WEATHER = 21;
    public static final byte DELTA_SPAWN_POS = 22;
    public static final byte DELTA_SERVER_DOWN = 23;
    public static final byte DELTA_PLAYER_STATE = 24;

    // Event ids; field layouts are positional and pinned by tests on both sides.
    public static final int EVENT_WORLD_INIT = 1;
    public static final int EVENT_WORLD_UNLOAD = 2;
    public static final int EVENT_CHUNK_LOAD = 3;
    public static final int EVENT_CHUNK_UNLOAD = 4;
    public static final int EVENT_CHUNK_SEND = 5;
    public static final int EVENT_TIME_CHANGE = 6;
    public static final int EVENT_WEATHER_CHANGE = 7;
    public static final int EVENT_DIFFICULTY_CHANGE = 8;
    public static final int EVENT_GAME_RULE_CHANGE = 9;
    public static final int EVENT_SPAWN_POSITION_CHANGE = 10;
    public static final int EVENT_BLOCK_BREAK = 20;
    public static final int EVENT_BLOCK_PLACE = 21;
    public static final int EVENT_BLOCK_UPDATE = 22;
    public static final int EVENT_SIGN_CHANGE = 23;
    public static final int EVENT_BLOCK_START_BREAK = 24;
    public static final int EVENT_ENTITY_SPAWN = 30;
    public static final int EVENT_ENTITY_DESPAWN = 31;
    public static final int EVENT_ENTITY_MOVE = 32;
    public static final int EVENT_ENTITY_MOTION = 33;
    public static final int EVENT_ENTITY_EFFECT = 34;
    public static final int EVENT_ENTITY_HEALTH_CHANGE = 35;
    public static final int EVENT_ENTITY_DAMAGE = 36;
    public static final int EVENT_ENTITY_REGAIN_HEALTH = 37;
    public static final int EVENT_ENTITY_DATA_UPDATE = 38;
    public static final int EVENT_ENTITY_TELEPORT = 39;
    public static final int EVENT_PLAYER_INTERACT = 50;
    public static final int EVENT_PLAYER_ITEM_HELD = 51;
    public static final int EVENT_PLAYER_DROP_ITEM = 52;
    public static final int EVENT_PLAYER_TOGGLE_SNEAK = 53;
    public static final int EVENT_PLAYER_TOGGLE_SPRINT = 54;
    public static final int EVENT_PLAYER_ANIMATION = 55;
    public static final int EVENT_PLAYER_DEATH = 56;
    public static final int EVENT_PLAYER_RESPAWN = 57;
    public static final int EVENT_PLAYER_GAME_MODE_CHANGE = 58;
    public static final int EVENT_PLAYER_CHANGE_DIMENSION = 59;
    public static final int EVENT_PLAYER_FORM_RESPOND = 60;
    public static final int EVENT_PLAYER_INTERACT_ENTITY = 61;
    public static final int EVENT_PLAYER_MOUSE_OVER = 62;
    public static final int EVENT_PLAYER_BED_ENTER = 63;
    public static final int EVENT_PLAYER_BED_LEAVE = 64;
    public static final int EVENT_INVENTORY_OPEN = 70;
    public static final int EVENT_INVENTORY_CLOSE = 71;
    public static final int EVENT_INVENTORY_UPDATE = 72;
    public static final int EVENT_CRAFT_ITEM = 73;
    public static final int EVENT_INVENTORY_TRANSACTION = 74;

    // Query ids (Java -> Go).
    public static final byte QUERY_GET_BLOCK = 1;
    public static final byte QUERY_CHUNK_LOADED = 2;
    public static final byte QUERY_CHUNK_INFO = 3;
    public static final byte QUERY_BLOCK_ENTITY = 4;
    public static final byte QUERY_ENTITY = 5;
    public static final byte QUERY_ENTITIES_NEAR = 6;
    public static final byte QUERY_PLAYER = 7;
    public static final byte QUERY_INVENTORY = 8;
    public static final byte QUERY_WORLD_LIST = 9;
    public static final byte QUERY_WORLD_META = 10;

    // Field types.
    public static final byte TYPE_NONE = 0;
    public static final byte TYPE_I32 = 1;
    public static final byte TYPE_I64 = 2;
    public static final byte TYPE_F32 = 3;
    public static final byte TYPE_BOOL = 4;
    public static final byte TYPE_STR = 5;
    public static final byte TYPE_VEC3I = 6;
    public static final byte TYPE_VEC3F = 7;
    public static final byte TYPE_ITEM = 8;
    public static final byte TYPE_BYTES = 9;

    private BridgeCodec() {
    }

    /**
     * One typed value in an event, query or RPC payload.
     */
    public record Field(byte type, int i32, long i64, float f32, boolean bool,
                        String str, int xi, int yi, int zi,
                        float xf, float yf, float zf,
                        WireItem item, byte[] bytes) {

        public static Field ofI32(int v) {
            return new Field(TYPE_I32, v, 0, 0, false, "", 0, 0, 0, 0, 0, 0, null, null);
        }

        public static Field ofI64(long v) {
            return new Field(TYPE_I64, 0, v, 0, false, "", 0, 0, 0, 0, 0, 0, null, null);
        }

        public static Field ofF32(float v) {
            return new Field(TYPE_F32, 0, 0, v, false, "", 0, 0, 0, 0, 0, 0, null, null);
        }

        public static Field ofBool(boolean v) {
            return new Field(TYPE_BOOL, 0, 0, 0, v, "", 0, 0, 0, 0, 0, 0, null, null);
        }

        public static Field ofStr(String v) {
            return new Field(TYPE_STR, 0, 0, 0, false, v == null ? "" : v, 0, 0, 0, 0, 0, 0, null, null);
        }

        public static Field ofVec3i(int x, int y, int z) {
            return new Field(TYPE_VEC3I, 0, 0, 0, false, "", x, y, z, 0, 0, 0, null, null);
        }

        public static Field ofVec3f(float x, float y, float z) {
            return new Field(TYPE_VEC3F, 0, 0, 0, false, "", 0, 0, 0, x, y, z, null, null);
        }

        public static Field ofItem(WireItem v) {
            return new Field(TYPE_ITEM, 0, 0, 0, false, "", 0, 0, 0, 0, 0, 0, v, null);
        }

        public static Field ofBytes(byte[] v) {
            return new Field(TYPE_BYTES, 0, 0, 0, false, "", 0, 0, 0, 0, 0, 0, null, v);
        }
    }

    /**
     * The wire form of an inventory item.
     */
    public record WireItem(String identifier, int damage, int count, long netId, byte[] nbt) {

        public static final WireItem AIR = new WireItem("", 0, 0, 0, null);

        public boolean isAir() {
            return (identifier == null || identifier.isEmpty()) && count == 0;
        }
    }

    /**
     * Growable little-endian buffer with the protocol primitives.
     */
    public static final class Writer {

        private ByteBuffer buf;

        public Writer(int capacity) {
            this.buf = ByteBuffer.allocate(Math.max(16, capacity)).order(ByteOrder.LITTLE_ENDIAN);
        }

        /**
         * Grows the buffer to fit n more bytes: chunk payloads reach megabytes,
         * so the writer can never assume the initial capacity holds.
         */
        private void ensure(int n) {
            if (this.buf.remaining() < n) {
                int needed = this.buf.position() + n;
                int grown = Math.max(this.buf.capacity() * 2, needed);
                ByteBuffer next = ByteBuffer.allocate(grown).order(ByteOrder.LITTLE_ENDIAN);
                next.put(this.buf.array(), 0, this.buf.position());
                this.buf = next;
            }
        }

        public void reset() {
            this.buf.clear();
        }

        public int length() {
            return this.buf.position();
        }

        public byte[] toArray() {
            byte[] out = new byte[this.buf.position()];
            System.arraycopy(this.buf.array(), 0, out, 0, out.length);
            return out;
        }

        public Writer u8(int v) {
            this.ensure(1);
            this.buf.put((byte) v);
            return this;
        }

        public Writer bool(boolean v) {
            this.ensure(1);
            this.buf.put((byte) (v ? 1 : 0));
            return this;
        }

        public Writer u16(int v) {
            this.ensure(2);
            this.buf.putShort((short) (v & 0xFFFF));
            return this;
        }

        public Writer i32(int v) {
            this.ensure(4);
            this.buf.putInt(v);
            return this;
        }

        public Writer i64(long v) {
            this.ensure(8);
            this.buf.putLong(v);
            return this;
        }

        public Writer f32(float v) {
            this.ensure(4);
            this.buf.putFloat(v);
            return this;
        }

        public Writer string(String s) {
            byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            int len = Math.min(bytes.length, 65535);
            this.ensure(2 + len);
            this.buf.putShort((short) len);
            this.buf.put(bytes, 0, len);
            return this;
        }

        public Writer bytes(byte[] b) {
            if (b == null) {
                b = new byte[0];
            }
            this.ensure(4 + b.length);
            this.buf.putInt(b.length);
            this.buf.put(b);
            return this;
        }

        public Writer vec3i(int x, int y, int z) {
            return this.i32(x).i32(y).i32(z);
        }

        public Writer vec3f(float x, float y, float z) {
            return this.f32(x).f32(y).f32(z);
        }

        public Writer item(WireItem item) {
            if (item == null) {
                item = WireItem.AIR;
            }
            this.string(item.identifier());
            this.i32(item.damage());
            this.i32(item.count());
            this.i64(item.netId());
            if (item.nbt() != null && item.nbt().length > 0) {
                this.bool(true);
                this.bytes(item.nbt());
            } else {
                this.bool(false);
            }
            return this;
        }

        public Writer field(Field f) {
            this.u8(f.type());
            switch (f.type()) {
                case TYPE_I32 -> this.i32(f.i32());
                case TYPE_I64 -> this.i64(f.i64());
                case TYPE_F32 -> this.f32(f.f32());
                case TYPE_BOOL -> this.bool(f.bool());
                case TYPE_STR -> this.string(f.str());
                case TYPE_VEC3I -> this.vec3i(f.xi(), f.yi(), f.zi());
                case TYPE_VEC3F -> this.vec3f(f.xf(), f.yf(), f.zf());
                case TYPE_ITEM -> this.item(f.item());
                case TYPE_BYTES -> this.bytes(f.bytes());
                default -> {
                }
            }
            return this;
        }

        public Writer fields(List<Field> fields) {
            for (Field f : fields) {
                this.field(f);
            }
            return this;
        }

        public Writer eventSync(long callId, int eventId, List<Field> fields) {
            this.u8(OP_EVENT_SYNC);
            this.i32((int) callId);
            this.u16(eventId);
            return this.fields(fields);
        }

        public Writer eventAsync(int eventId, List<Field> fields) {
            this.u8(OP_EVENT_ASYNC);
            this.u16(eventId);
            return this.fields(fields);
        }

        public Writer deltaHeader(byte deltaType) {
            this.u8(OP_DELTA);
            this.u8(deltaType);
            return this;
        }

        public Writer hello(String proxyVersion, boolean worldView, int retentionSec, int maxChunks, int syncTimeoutMs) {
            this.u8(OP_HELLO);
            this.u8(PROTOCOL_VERSION);
            this.string(proxyVersion);
            this.bool(worldView);
            this.i32(retentionSec);
            this.i32(maxChunks);
            this.i32(syncTimeoutMs);
            return this;
        }

        public Writer query(long callId, byte queryType, List<Field> fields) {
            this.u8(OP_QUERY);
            this.i32((int) callId);
            this.u8(queryType);
            return this.fields(fields);
        }

        public Writer rpcResult(long callId, boolean ok, List<Field> result, String error) {
            this.u8(OP_RPC_RESULT);
            this.i32((int) callId);
            this.bool(ok);
            if (!ok) {
                this.string(error == null ? "proxy call failed" : error);
            }
            return this.fields(ok && result != null ? result : List.of());
        }
    }

    /**
     * Bounds-checked reader over one frame payload.
     */
    public static final class Reader {

        private final ByteBuffer buf;

        public Reader(byte[] payload) {
            this.buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        public int remaining() {
            return this.buf.remaining();
        }

        public int u8() throws IOException {
            if (this.buf.remaining() < 1) {
                throw new EOFException("bridge payload truncated");
            }
            return this.buf.get() & 0xFF;
        }

        public int u16() throws IOException {
            if (this.buf.remaining() < 2) {
                throw new EOFException("bridge payload truncated");
            }
            return this.buf.getShort() & 0xFFFF;
        }

        public boolean bool() throws IOException {
            return this.u8() != 0;
        }

        public int i32() throws IOException {
            if (this.buf.remaining() < 4) {
                throw new EOFException("bridge payload truncated");
            }
            return this.buf.getInt();
        }

        public long i64() throws IOException {
            if (this.buf.remaining() < 8) {
                throw new EOFException("bridge payload truncated");
            }
            return this.buf.getLong();
        }

        public float f32() throws IOException {
            if (this.buf.remaining() < 4) {
                throw new EOFException("bridge payload truncated");
            }
            return this.buf.getFloat();
        }

        public String string() throws IOException {
            if (this.buf.remaining() < 2) {
                throw new EOFException("bridge payload truncated");
            }
            int len = this.buf.getShort() & 0xFFFF;
            if (len > this.buf.remaining()) {
                throw new EOFException("bridge payload truncated");
            }
            byte[] bytes = new byte[len];
            this.buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public byte[] bytes() throws IOException {
            if (this.buf.remaining() < 4) {
                throw new EOFException("bridge payload truncated");
            }
            int len = this.buf.getInt();
            if (len < 0 || len > this.buf.remaining()) {
                throw new EOFException("bridge payload truncated");
            }
            byte[] bytes = new byte[len];
            this.buf.get(bytes);
            return bytes;
        }

        public Field field() throws IOException {
            int type = this.u8();
            return switch (type) {
                case TYPE_I32 -> Field.ofI32(this.i32());
                case TYPE_I64 -> Field.ofI64(this.i64());
                case TYPE_F32 -> Field.ofF32(this.f32());
                case TYPE_BOOL -> Field.ofBool(this.bool());
                case TYPE_STR -> Field.ofStr(this.string());
                case TYPE_VEC3I -> {
                    int x = this.i32();
                    int y = this.i32();
                    yield Field.ofVec3i(x, y, this.i32());
                }
                case TYPE_VEC3F -> {
                    float x = this.f32();
                    float y = this.f32();
                    yield Field.ofVec3f(x, y, this.f32());
                }
                case TYPE_ITEM -> Field.ofItem(this.item());
                case TYPE_BYTES -> Field.ofBytes(this.bytes());
                default -> Field.ofStr("");
            };
        }

        public List<Field> remainingFields() throws IOException {
            List<Field> fields = new ArrayList<>();
            while (this.buf.remaining() > 0) {
                fields.add(this.field());
            }
            return fields;
        }

        public WireItem item() throws IOException {
            String id = this.string();
            int damage = this.i32();
            int count = this.i32();
            long netId = this.i64();
            boolean hasNbt = this.bool();
            byte[] nbt = hasNbt ? this.bytes() : null;
            return new WireItem(id, damage, count, netId, nbt);
        }

        public HelloAck helloAck() throws IOException {
            int proto = this.u8();
            if (proto != PROTOCOL_VERSION) {
                throw new IOException("Go runtime speaks bridge protocol " + proto + ", proxy expects " + PROTOCOL_VERSION);
            }
            String goVersion = this.string();
            String apiVersion = this.string();
            int count = this.buf.getShort() & 0xFFFF;
            List<String> plugins = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                plugins.add(this.string());
            }
            return new HelloAck(goVersion, apiVersion, plugins);
        }

        public List<PluginStatus> pluginStatus() throws IOException {
            int count = this.buf.getShort() & 0xFFFF;
            List<PluginStatus> statuses = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = this.string();
                String version = this.string();
                String apiVersion = this.string();
                int state = this.u8();
                String error = this.string();
                statuses.add(new PluginStatus(name, version, apiVersion, state, error));
            }
            return statuses;
        }
    }

    public record HelloAck(String goVersion, String apiVersion, List<String> plugins) {
    }

    public record PluginStatus(String name, String version, String apiVersion, int state, String error) {
    }

    /**
     * Reads one length-prefixed frame; blocks until complete.
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf);
        int length = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("bridge frame length out of range: " + length);
        }
        byte[] payload = new byte[length];
        readFully(in, payload);
        return payload;
    }

    /**
     * Writes one length-prefixed frame and flushes.
     */
    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("bridge frame too large: " + payload.length);
        }
        ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        lenBuf.putInt(payload.length);
        out.write(lenBuf.array());
        out.write(payload);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                throw new EOFException("bridge stream ended");
            }
            read += n;
        }
    }
}
