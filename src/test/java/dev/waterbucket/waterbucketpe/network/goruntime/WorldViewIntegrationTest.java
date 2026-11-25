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

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldService;
import dev.waterbucket.waterbucketpe.utils.config.proxy.WorldViewSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end against the real Go sidecar: the proxy feeds a real chunk delta,
 * the Go runtime decodes it into the model, a real example plugin (WorldGuard)
 * reads the block and cancels a bedrock break through the bridge verdict path.
 * <p>
 * Skipped when the Go binaries have not been built (build-go.sh) - CI and
 * local runs with a built runtime execute the full chain.
 */
class WorldViewIntegrationTest {

    @TempDir
    Path dataDir;

    private GoRuntimeManager runtime;
    private WorldService service;

    @BeforeAll
    static void requireGoToolchainHint() {
        // Nothing to do: the binary check happens per-test.
    }

    private Path binaryPath() {
        return Path.of("build/go-runtime/waterbucket-go-runtime-linux-amd64");
    }

    @AfterEach
    void tearDown() {
        if (this.runtime != null) {
            this.runtime.shutdown();
        }
    }

    /**
     * Builds one v9 subchunk (4 bits per block, palette [air, bedrock]) with
     * block index 0 set to bedrock - the same encoder shape the Go tests use,
     * so both sides agree on the blob format.
     */
    private static byte[] chunkBlobWithBedrock() throws Exception {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(0x09); // version 9
        blob.write(4);    // subchunk index 4 -> y 0..15
        blob.write(8);    // subchunk count
        blob.write(4);    // bits per block
        writeInt32LE(blob, 4096);
        // packed words: 4096 blocks * 4 bits = 512 words; index 0 = palette slot 1 (bedrock)
        byte[] words = new byte[512 * 4];
        words[0] = 0x01;
        blob.write(words);
        // palette: varint count 2, then two NBT compounds
        blob.write(2);
        blob.write(paletteEntry("minecraft:air"));
        blob.write(paletteEntry("minecraft:bedrock"));
        return blob.toByteArray();
    }

    private static byte[] paletteEntry(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10); // TAG_Compound
        out.write(0);  // root name: varint len 0
        // "name" string
        out.write(8);
        out.write(4);
        out.write("name".getBytes("UTF-8"));
        byte[] nameBytes = name.getBytes("UTF-8");
        out.write(nameBytes.length);
        out.write(nameBytes);
        // "states" empty compound
        out.write(10);
        out.write(6);
        out.write("states".getBytes("UTF-8"));
        out.write(0);
        // "version" int
        out.write(3);
        out.write(7);
        out.write("version".getBytes("UTF-8"));
        writeInt32LE(out, 1792);
        out.write(0); // end root
        return out.toByteArray();
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private void awaitTrue(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for " + what);
    }

    @Test
    void sidecarBuildsTheModelAndPluginsCancelThroughTheBridge() throws Exception {
        Path binary = binaryPath();
        assumeTrue(Files.exists(binary), "Go runtime binary not built; run build-go.sh");

        // Stage the runtime where the manager extracts/looks for it, plus the
        // real WorldGuard example plugin.
        Path runtimeDir = this.dataDir.resolve("go-runtime");
        Files.createDirectories(runtimeDir);
        Files.copy(binary, runtimeDir.resolve(binary.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Path pluginsDir = this.dataDir.resolve("plugins-go");
        Path guard = pluginsDir.resolve("world-guard");
        Files.createDirectories(guard);
        for (String file : new String[]{"plugin.yml", "main.go"}) {
            Files.copy(Path.of("examples/plugins/go/world-guard", file),
                    guard.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }

        ProxyServer proxy = Mockito.mock(ProxyServer.class);
        Mockito.when(proxy.getDataPath()).thenReturn(this.dataDir);
        WorldViewSettings settings = new WorldViewSettings();
        settings.enable(true);
        settings.goRuntime(true);
        settings.retentionSeconds(60);
        settings.maxChunksPerWorld(256);
        settings.eventSyncTimeoutMs(2000);
        settings.goPluginsDir(this.dataDir.relativize(pluginsDir).toString());

        this.runtime = new GoRuntimeManager(proxy, settings);
        this.service = new WorldService(this.runtime);
        this.runtime.start();
        awaitTrue("sidecar connection", () -> this.runtime.isConnected());
        assertNotNull(this.runtime.helloAck());
        assertEquals("1.6.0", this.runtime.helloAck().apiVersion());
        awaitTrue("WorldGuard plugin enabled", () -> this.runtime.pluginStatuses().stream()
                .anyMatch(s -> s.name().equals("WorldGuard") && s.state() == BridgeCodec.PLUGIN_ENABLED));

        // Feed one chunk: (0,0,0) becomes bedrock in the Go model.
        BridgeCodec.Writer chunk = new BridgeCodec.Writer(512);
        chunk.deltaHeader(BridgeCodec.DELTA_CHUNK);
        chunk.string("lobby");
        chunk.i32(0);
        chunk.i32(0);
        chunk.i32(0);
        chunk.bytes(chunkBlobWithBedrock());
        byte[] chunkFrame = chunk.toArray();
        System.out.println("[itest] chunk frame bytes: " + chunkFrame.length);
        this.runtime.sendAsync(chunkFrame);

        awaitTrue("bedrock visible in the model", () ->
                "minecraft:bedrock".equals(this.service.getBlock("lobby", 0, new dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos(0, 0, 0)).name()));

        // A plugin verdict through the bridge: breaking the bedrock cancels,
        // breaking the air next to it does not.
        List<BridgeCodec.Field> bedrockBreak = List.of(
                BridgeCodec.Field.ofStr("lobby"),
                BridgeCodec.Field.ofI32(0),
                BridgeCodec.Field.ofStr("Steve"),
                BridgeCodec.Field.ofVec3i(0, 0, 0),
                BridgeCodec.Field.ofItem(BridgeCodec.WireItem.AIR),
                BridgeCodec.Field.ofI32(2));
        assertTrue(this.runtime.isEventCancelled(BridgeCodec.EVENT_BLOCK_BREAK, bedrockBreak, 2000),
                "WorldGuard must cancel bedrock breaks");

        List<BridgeCodec.Field> airBreak = List.of(
                BridgeCodec.Field.ofStr("lobby"),
                BridgeCodec.Field.ofI32(0),
                BridgeCodec.Field.ofStr("Steve"),
                BridgeCodec.Field.ofVec3i(1, 0, 0),
                BridgeCodec.Field.ofItem(BridgeCodec.WireItem.AIR),
                BridgeCodec.Field.ofI32(2));
        assertFalse(this.runtime.isEventCancelled(BridgeCodec.EVENT_BLOCK_BREAK, airBreak, 2000));

        // Chunk queries answer from the model.
        assertTrue(this.service.isChunkLoaded("lobby", 0, 0, 0));
        assertEquals(1, this.service.getChunk("lobby", 0, 0, 0).subchunks());
    }
}
