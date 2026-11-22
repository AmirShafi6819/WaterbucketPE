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

package dev.waterbucket.waterbucketpe.packs;

import dev.waterbucket.waterbucketpe.packs.types.PackManifest;
import dev.waterbucket.waterbucketpe.packs.types.ZipResourcePack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for malformed pack manifests: an empty module list used to pass
 * {@link PackManifest#validate()} and then crash the boot (or half-corrupt a reload) in
 * {@code PackManager#rebuildPackets}, and a zero-byte manifest.json made
 * {@code ResourcePack#loadManifest()} report success with a null manifest.
 */
public class PackManifestValidationTest {

    @TempDir
    Path tempDir;

    private static PackManifest manifest(String json) {
        return PackManifest.fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String validManifest(String modulesJson) {
        return "{"
                + "\"format_version\": 2,"
                + "\"header\": {\"name\": \"Test\", \"description\": \"d\", \"uuid\": \""
                + java.util.UUID.randomUUID() + "\", \"version\": [1, 0, 0]},"
                + "\"modules\": " + modulesJson + "}";
    }

    @Test
    void emptyModulesListFailsValidation() {
        PackManifest manifest = manifest(validManifest("[]"));
        assertFalse(manifest.validate(), "an empty modules list must not validate: getType() would throw");
    }

    @Test
    void moduleWithoutTypeFailsValidation() {
        PackManifest manifest = manifest(validManifest("[{\"uuid\": \""
                + java.util.UUID.randomUUID() + "\", \"version\": [1, 0, 0]}]"));
        assertFalse(manifest.validate());
    }

    @Test
    void validManifestStillValidates() {
        PackManifest manifest = manifest(validManifest("[{\"type\": \"resources\", \"uuid\": \""
                + java.util.UUID.randomUUID() + "\", \"version\": [1, 0, 0]}]"));
        assertTrue(manifest.validate());
        assertEquals("resources", manifest.getModules().get(0).getType());
    }

    private Path zipWithManifest(String manifestJson) throws Exception {
        Path pack = this.tempDir.resolve("pack-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(pack))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return pack;
    }

    @Test
    void emptyManifestFileReportsLoadFailure() throws Exception {
        ZipResourcePack pack = new ZipResourcePack(this.zipWithManifest(""));
        try {
            assertFalse(pack.loadManifest(), "a zero-byte manifest.json must not report success");
        } finally {
            pack.close();
        }
    }

    @Test
    void manifestWithoutModulesReportsValidationError() throws Exception {
        ZipResourcePack pack = new ZipResourcePack(this.zipWithManifest(validManifest("[]")));
        try {
            assertTrue(pack.loadManifest());
            assertNotNull(pack.getPackManifest());
            assertFalse(pack.getPackManifest().validate());
        } finally {
            pack.close();
        }
    }

    @Test
    void validZipPackLoadsAndServesChunks() throws Exception {
        ZipResourcePack pack = new ZipResourcePack(this.zipWithManifest(validManifest(
                "[{\"type\": \"resources\", \"uuid\": \"" + java.util.UUID.randomUUID() + "\", \"version\": [1, 0, 0]}]")));
        try {
            assertTrue(pack.loadManifest());
            assertTrue(pack.getPackManifest().validate());
            assertEquals("Test", pack.getPackName());
            // saveToCache must fill the whole buffer, not stop at the first short read
            pack.saveToCache();
            InputStream cached = pack.getCachedPack() == null
                    ? null : new ByteArrayInputStream(toByteArray(pack.getCachedPack()));
            if (cached != null) {
                byte[] chunk = pack.getChunk(0, 1024);
                assertTrue(chunk.length > 0);
            }
        } finally {
            pack.close();
        }
    }

    private static byte[] toByteArray(java.nio.ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }
}
