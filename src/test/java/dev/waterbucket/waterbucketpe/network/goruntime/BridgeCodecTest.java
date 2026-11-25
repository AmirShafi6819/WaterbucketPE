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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format conformance tests: the golden vectors here mirror the Go side's
 * bridge tests byte for byte, so Java and Go cannot drift apart.
 */
class BridgeCodecTest {

    @Test
    void eventSyncMatchesGoGoldenVector() throws IOException {
        BridgeCodec.Writer w = new BridgeCodec.Writer(128);
        List<BridgeCodec.Field> fields = List.of(
                BridgeCodec.Field.ofStr("lobby"),
                BridgeCodec.Field.ofI32(0),
                BridgeCodec.Field.ofStr("Steve"),
                BridgeCodec.Field.ofVec3i(100, 64, -200),
                BridgeCodec.Field.ofItem(new BridgeCodec.WireItem("minecraft:dirt", 0, 1, 0, null)),
                BridgeCodec.Field.ofI32(1));
        w.eventSync(42, BridgeCodec.EVENT_BLOCK_BREAK, fields);
        byte[] frame = w.toArray();

        assertEquals(BridgeCodec.OP_EVENT_SYNC, frame[0]);
        // call id 42 as i32 LE
        assertEquals(42, frame[1]);
        assertEquals(0, frame[2]);
        assertEquals(0, frame[3]);
        assertEquals(0, frame[4]);
        // event id 20 (BlockBreak) as u16 LE
        assertEquals(20, frame[5]);
        assertEquals(0, frame[6]);

        BridgeCodec.Reader r = new BridgeCodec.Reader(frame);
        assertEquals(BridgeCodec.OP_EVENT_SYNC, r.u8());
        assertEquals(42L, r.i32());
        assertEquals(20, r.u16());
        List<BridgeCodec.Field> decoded = r.remainingFields();
        assertEquals(6, decoded.size());
        assertEquals("lobby", decoded.get(0).str());
        assertEquals(0, decoded.get(1).i32());
        assertEquals("Steve", decoded.get(2).str());
        assertEquals(100, decoded.get(3).xi());
        assertEquals(64, decoded.get(3).yi());
        assertEquals(-200, decoded.get(3).zi());
        assertEquals("minecraft:dirt", decoded.get(4).item().identifier());
        assertEquals(1, decoded.get(5).i32());
        assertEquals(0, r.remaining());
    }

    @Test
    void helloAckDecodes() throws IOException {
        BridgeCodec.Writer w = new BridgeCodec.Writer(64);
        w.u8(BridgeCodec.OP_HELLO_ACK);
        w.u8(BridgeCodec.PROTOCOL_VERSION);
        w.string("go1.22.10");
        w.string("1.6.0");
        w.u16(2);
        w.string("econ");
        w.string("guard");

        BridgeCodec.Reader r = new BridgeCodec.Reader(w.toArray());
        assertEquals(BridgeCodec.OP_HELLO_ACK, r.u8());
        BridgeCodec.HelloAck ack = r.helloAck();
        assertEquals("go1.22.10", ack.goVersion());
        assertEquals("1.6.0", ack.apiVersion());
        assertEquals(List.of("econ", "guard"), ack.plugins());
    }

    @Test
    void pluginStatusRoundtrip() throws IOException {
        BridgeCodec.Writer w = new BridgeCodec.Writer(128);
        w.u8(BridgeCodec.OP_PLUGIN_STATUS);
        w.u16(2);
        w.string("WorldGuard").string("1.0.0").string("1.6.0").u8(BridgeCodec.PLUGIN_ENABLED).string("");
        w.string("Broken").string("0.1").string("1.0.0").u8(BridgeCodec.PLUGIN_FAILED).string("boom");

        BridgeCodec.Reader r = new BridgeCodec.Reader(w.toArray());
        assertEquals(BridgeCodec.OP_PLUGIN_STATUS, r.u8());
        List<BridgeCodec.PluginStatus> statuses = r.pluginStatus();
        assertEquals(2, statuses.size());
        assertEquals(BridgeCodec.PLUGIN_ENABLED, statuses.get(0).state());
        assertEquals("boom", statuses.get(1).error());
    }

    @Test
    void itemWireFormRoundtripsWithNbt() throws IOException {
        BridgeCodec.Writer w = new BridgeCodec.Writer(64);
        w.item(new BridgeCodec.WireItem("minecraft:iron_sword", 3, 2, 77L,
                new byte[]{10, 0, 10, 0}));
        BridgeCodec.Reader r = new BridgeCodec.Reader(w.toArray());
        BridgeCodec.WireItem item = r.item();
        assertEquals("minecraft:iron_sword", item.identifier());
        assertEquals(3, item.damage());
        assertEquals(2, item.count());
        assertEquals(77L, item.netId());
        assertArrayEquals(new byte[]{10, 0, 10, 0}, item.nbt());
    }

    @Test
    void frameRoundtrip() throws IOException {
        byte[] payload = {1, 2, 3, 4, 5, 6, 7};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BridgeCodec.writeFrame(out, payload);
        byte[] frame = BridgeCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertArrayEquals(payload, frame);
    }

    @Test
    void truncatedPayloadFailsClean() {
        BridgeCodec.Writer w = new BridgeCodec.Writer(16);
        w.string("needs more bytes");
        BridgeCodec.Reader r = new BridgeCodec.Reader(Arrays.copyOf(w.toArray(), 5));
        assertThrows(IOException.class, r::string);
    }

    @Test
    void helloEncodesWorldViewHandshake() throws IOException {
        BridgeCodec.Writer w = new BridgeCodec.Writer(64);
        w.hello("1.6.0", true, 300, 4096, 8);
        BridgeCodec.Reader r = new BridgeCodec.Reader(w.toArray());
        assertEquals(BridgeCodec.OP_HELLO, r.u8());
        assertEquals(BridgeCodec.PROTOCOL_VERSION, r.u8());
        assertEquals("1.6.0", r.string());
        assertTrue(r.bool());
        assertEquals(300, r.i32());
        assertEquals(4096, r.i32());
        assertEquals(8, r.i32());
        assertFalse(r.remaining() > 0);
    }
}
