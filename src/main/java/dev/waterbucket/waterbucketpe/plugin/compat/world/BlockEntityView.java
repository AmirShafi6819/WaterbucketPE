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

package dev.waterbucket.waterbucketpe.plugin.compat.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A block entity (sign, chest, spawner...) with its parsed NBT tree.
 */
public record BlockEntityView(String type, BlockPos position, Map<String, Object> nbt) {

    /**
     * The text of a sign block entity: front text first, then back text, using
     * the modern FrontText/BackText layout with a legacy Text fallback.
     */
    public List<String> signText() {
        List<String> lines = new ArrayList<>();
        this.signSide("FrontText", lines);
        if (lines.isEmpty()) {
            Object legacy = this.nbt == null ? null : this.nbt.get("Text");
            if (legacy instanceof String s && !s.isEmpty()) {
                lines.add(s);
            }
        }
        this.signSide("BackText", lines);
        return lines;
    }

    private void signSide(String key, List<String> lines) {
        if (this.nbt == null) {
            return;
        }
        Object side = this.nbt.get(key);
        if (side instanceof Map<?, ?> map) {
            Object text = map.get("Text");
            if (text instanceof String s && !s.isEmpty()) {
                lines.add(s);
            }
        }
    }
}
