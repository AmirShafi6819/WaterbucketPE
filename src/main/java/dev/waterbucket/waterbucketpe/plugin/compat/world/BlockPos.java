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

/**
 * An integer block position in world space.
 */
public record BlockPos(int x, int y, int z) {

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(this.x + dx, this.y + dy, this.z + dz);
    }

    public int chunkX() {
        return this.x >> 4;
    }

    public int chunkZ() {
        return this.z >> 4;
    }

    @Override
    public String toString() {
        return this.x + ", " + this.y + ", " + this.z;
    }
}
