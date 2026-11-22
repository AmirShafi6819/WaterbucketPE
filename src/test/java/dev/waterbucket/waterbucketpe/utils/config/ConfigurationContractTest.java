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

package dev.waterbucket.waterbucketpe.utils.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link Configuration} accessor contracts: missing keys used to come
 * back as the string "null", null defaults threw NumberFormatException, quoted booleans threw
 * ClassCastException and an empty document left the whole config NPE-ing on first use.
 */
public class ConfigurationContractTest {

    @TempDir
    Path tempDir;

    private YamlConfig configWith(String yaml) throws Exception {
        Path file = this.tempDir.resolve("config" + System.nanoTime() + ".yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new YamlConfig(file.toFile());
    }

    @Test
    void emptyDocumentYieldsUsableConfig() throws Exception {
        YamlConfig config = this.configWith("");
        assertTrue(config.getKeys().isEmpty());
        assertEquals("fallback", config.getString("anything", "fallback"));
        assertDoesNotThrow(config::getKeys);
    }

    @Test
    void getStringReturnsTheDefaultValueForMissingKeys() throws Exception {
        YamlConfig config = this.configWith("present: value\n");
        // The old String.valueOf() implementation returned the four-character string "null"
        assertEquals("missing", config.getString("absent", "missing"));
        assertNull(config.getString("absent", null));
        assertEquals("value", config.getString("present", "fallback"));
    }

    @Test
    void numericAccessorsReturnTheDefaultForMissingKeys() throws Exception {
        YamlConfig config = this.configWith("port: 19132\n");
        assertEquals(8080, config.getInt("missing", 8080));
        assertEquals(19132, config.getInt("port", 1));
        assertNull(config.getInt("missing", null));
        assertEquals(2L, config.getLong("missing", 2L));
        assertEquals(2.5, config.getDouble("missing", 2.5));
    }

    @Test
    void numericAccessorsFallBackOnGarbageValues() throws Exception {
        YamlConfig config = this.configWith("level: not-a-number\n");
        assertEquals(6, config.getInt("level", 6));
    }

    @Test
    void booleanAccessorAcceptsQuotedValues() throws Exception {
        YamlConfig config = this.configWith("flag: \"true\"\nother: nope\n");
        assertTrue(config.getBoolean("flag", false));
        assertFalse(config.getBoolean("other", true));
        assertEquals(null, config.getBoolean("absent", null));
    }

    @Test
    void scalarIntermediateNodeReturnsTheDefaultInsteadOfThrowing() throws Exception {
        YamlConfig config = this.configWith("a: 5\n");
        // key "a.b" where "a" is a scalar used to ClassCastException inside get()
        assertEquals("def", config.getString("a.b", "def"));
    }

    @Test
    void nonAsciiValuesSurviveASaveLoadRoundTrip() throws Exception {
        Path file = this.tempDir.resolve("utf8.yml");
        YamlConfig config = new YamlConfig(file.toFile());
        config.setString("motd", "Привет 世界");
        config.save();

        YamlConfig reloaded = new YamlConfig(file.toFile());
        assertEquals("Привет 世界", reloaded.getString("motd", null));
        // The file itself is UTF-8 encoded
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("Привет 世界"));
    }

    @Test
    void callerProvidedStreamIsStillLoadedWithoutClosingIt() throws Exception {
        // Caller-provided streams stay the caller's responsibility; only streams the config
        // opens itself are closed by the constructor.
        InputStream stream = new ByteArrayInputStream("key: value\n".getBytes(StandardCharsets.UTF_8));
        YamlConfig config = new YamlConfig(this.tempDir.resolve("provided.yml").toFile(), stream);
        assertEquals("value", config.getString("key", null));
    }

    @Test
    void malformedYamlDoesNotThrowAndKeepsTheConfigUsable() throws Exception {
        YamlConfig config = this.configWith("::: not: valid: yaml: [");
        assertTrue(config.getKeys().isEmpty());
        assertEquals("fallback", config.getString("a", "fallback"));
    }
}
