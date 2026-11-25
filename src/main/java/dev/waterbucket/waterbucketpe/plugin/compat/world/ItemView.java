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

import java.util.List;
import java.util.Map;

/**
 * One inventory item exactly as the proxy observed it flowing to or from a
 * client: identifier, damage, count, network id and the raw NBT tree.
 */
public record ItemView(String identifier, int damage, int count, long netId, Map<String, Object> nbt) {

    /**
     * The shared empty item.
     */
    public static final ItemView AIR = new ItemView("minecraft:air", 0, 0, 0, Map.of());

    public boolean isAir() {
        return (this.identifier == null || this.identifier.isEmpty()
                || this.identifier.equals("minecraft:air")) && this.count == 0;
    }

    /**
     * The identifier, or "Air" when empty.
     */
    public String name() {
        return this.identifier == null || this.identifier.isEmpty() ? "Air" : this.identifier;
    }

    /**
     * The display name from the item's NBT ({@code display.Name}), or "".
     */
    @SuppressWarnings("unchecked")
    public String customName() {
        if (this.nbt == null) {
            return "";
        }
        Object display = this.nbt.get("display");
        if (display instanceof Map<?, ?> map) {
            Object name = map.get("Name");
            return name instanceof String s ? s : "";
        }
        return "";
    }

    /**
     * The stored enchantment list (maps with {@code id} and {@code lvl}).
     */
    public List<Map<String, Object>> enchantments() {
        if (this.nbt == null) {
            return List.of();
        }
        Object ench = this.nbt.get("ench");
        if (ench instanceof List<?> list) {
            return list.stream()
                    .filter(e -> e instanceof Map)
                    .map(e -> (Map<String, Object>) e)
                    .toList();
        }
        return List.of();
    }
}
