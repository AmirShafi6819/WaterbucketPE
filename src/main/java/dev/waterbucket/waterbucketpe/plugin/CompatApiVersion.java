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

/**
 * The plugin API version contract, shared by every loader (Java, Python, PHP
 * and Go). A plugin declares which API generation it targets:
 * <ul>
 *   <li>{@link #CURRENT} - this build's generation, full surface, recommended;</li>
 *   <li>{@link #LEGACY} - the 1.0.0 wildcard: loads on every generation for
 *   plugins that only touch the old, stable surface;</li>
 *   <li>anything else - rejected before any plugin code runs, with the exact
 *   reason in the log.</li>
 * </ul>
 */
public final class CompatApiVersion {

    /**
     * This build's plugin API generation.
     */
    public static final String CURRENT = "1.6.0";

    /**
     * The legacy wildcard that loads on every generation.
     */
    public static final String LEGACY = "1.0.0";

    private CompatApiVersion() {
    }

    /**
     * Normalizes a declared api-version: missing or blank counts as the
     * legacy wildcard, exactly like plugins that predate the field.
     */
    public static String normalize(String declared) {
        return declared == null || declared.isBlank() ? LEGACY : declared.trim();
    }

    /**
     * Returns null when the declared api-version may load, or a human-readable
     * rejection reason when it may not.
     */
    public static String check(String declared) {
        String normalized = normalize(declared);
        if (CURRENT.equals(normalized) || LEGACY.equals(normalized)) {
            return null;
        }
        return "plugin declares api-version '" + normalized + "'; this build supports '"
                + CURRENT + "' (or '" + LEGACY + "' for legacy plugins that run on every generation)";
    }
}
