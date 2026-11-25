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

/**
 * One observed inventory window of a player (main inventory, hotbar, armor,
 * offhand, an open container...). Only what the proxy actually saw is filled.
 */
public record InventoryView(int windowId, String holderName, List<ItemView> items) {

    // Well-known window ids on the Bedrock protocol.
    public static final int WINDOW_PLAYER = 12;
    public static final int WINDOW_OFFHAND = 17;

    public int size() {
        return this.items.size();
    }

    public ItemView get(int slot) {
        if (slot < 0 || slot >= this.items.size()) {
            return ItemView.AIR;
        }
        return this.items.get(slot);
    }
}
