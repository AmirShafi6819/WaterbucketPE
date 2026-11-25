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
 * A loaded chunk in the live world model: how many subchunks arrived and the
 * block entities observed on it, keyed by packed position
 * ({@code (x & 0x3FFFFFF) << 38 | (z & 0x3FFFFFF) << 12 | y}).
 */
public record ChunkView(int dim, int cx, int cz, int subchunks, Map<Long, BlockEntityView> blockEntities) {

    public static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public boolean isLoaded() {
        return this.subchunks > 0 || (this.blockEntities != null && !this.blockEntities.isEmpty());
    }
}
