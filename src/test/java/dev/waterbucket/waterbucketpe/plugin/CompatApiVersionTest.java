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

package dev.waterbucket.waterbucketpe.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The api-version contract: 1.6.0 loads, 1.0.0 is the legacy wildcard that
 * runs on every generation, everything else is rejected with a clear reason.
 */
class CompatApiVersionTest {

    @Test
    void currentVersionLoads() {
        assertNull(CompatApiVersion.check("1.6.0"));
    }

    @Test
    void legacyWildcardLoads() {
        assertNull(CompatApiVersion.check("1.0.0"));
    }

    @Test
    void missingDeclarationIsLegacy() {
        assertNull(CompatApiVersion.check(null));
        assertNull(CompatApiVersion.check(""));
        assertNull(CompatApiVersion.check("   "));
        assertEquals("1.0.0", CompatApiVersion.normalize(null));
    }

    @Test
    void unknownVersionsAreRejected() {
        for (String bad : new String[]{"2.0.0", "1.5.6", "0.9.9", "1.6.1"}) {
            String error = CompatApiVersion.check(bad);
            assertNotNull(error, bad + " must be rejected");
            assertTrue(error.contains("1.6.0"), "rejection names the supported generation");
            assertTrue(error.contains("1.0.0"), "rejection names the legacy wildcard");
        }
    }
}
