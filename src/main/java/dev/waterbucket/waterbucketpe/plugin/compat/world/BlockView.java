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

import java.util.Map;

/**
 * One block as resolved from the live world model: the identifier, the
 * runtime id (when the block table was available) and its state properties.
 */
public record BlockView(String name, int runtimeId, BlockPos position, Map<String, String> properties) {

    /**
     * The view for positions the model has not seen (no chunk loaded there).
     */
    public static final BlockView UNLOADED = new BlockView("minecraft:unloaded", -1,
            new BlockPos(0, 0, 0), Map.of());

    public boolean isAir() {
        return "minecraft:air".equals(this.name) || this.name.isEmpty();
    }

    public boolean isLoaded() {
        return !"minecraft:unloaded".equals(this.name);
    }

    /**
     * One state property, e.g. {@code facing} for a stairs block.
     */
    public String property(String key) {
        return this.properties == null ? null : this.properties.get(key);
    }
}
