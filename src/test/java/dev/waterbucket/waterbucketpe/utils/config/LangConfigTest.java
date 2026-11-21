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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Language files written for WaterdogPE keep working: their legacy "waterdog." keys resolve
 * when a "waterbucket." key is not present.
 */
public class LangConfigTest {

    @TempDir
    Path folder;

    @Test
    void forkKeysResolveDirectly() throws Exception {
        File file = this.write("waterbucket.server.shutdown=Proxy shutdown\n");
        LangConfig config = new LangConfig(file);
        assertEquals("Proxy shutdown", config.translateString("waterbucket.server.shutdown"));
    }

    @Test
    void legacyWaterdogKeysResolveThroughFallback() throws Exception {
        File file = this.write("waterdog.server.shutdown=Proxy shutdown\n");
        LangConfig config = new LangConfig(file);
        assertEquals("Proxy shutdown", config.translateString("waterbucket.server.shutdown"));
    }

    @Test
    void forkKeysTakePriorityOverLegacyOnes() throws Exception {
        File file = this.write("waterdog.server.shutdown=Legacy message\nwaterbucket.server.shutdown=New message\n");
        LangConfig config = new LangConfig(file);
        assertEquals("New message", config.translateString("waterbucket.server.shutdown"));
    }

    @Test
    void keysWithoutTheForkPrefixAreNotTranslated() throws Exception {
        File file = this.write("waterdog.server.shutdown=Proxy shutdown\n");
        LangConfig config = new LangConfig(file);
        // Unknown keys are returned verbatim, like upstream does
        assertEquals("waterbucket.unknown.key", config.translateString("waterbucket.unknown.key"));
        assertEquals("some.other.key", config.translateString("some.other.key"));
    }

    private File write(String content) throws Exception {
        File file = Files.createFile(this.folder.resolve("lang.ini")).toFile();
        Files.writeString(file.toPath(), content);
        return file;
    }
}
