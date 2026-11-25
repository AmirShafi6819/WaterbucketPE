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
 * A live entity snapshot: identifier, position, motion, rotation, metadata
 * flags and tracked attributes. Built from what the proxy actually observed.
 */
public record EntityView(long eid, String type, int dim,
                         float x, float y, float z,
                         float motX, float motY, float motZ,
                         float yaw, float pitch,
                         long flags, Map<String, Float> attrs,
                         boolean player, String name, String xuid) {

    // Bedrock entity flag ordinals (subset of the well-known layout).
    public static final int FLAG_ON_FIRE = 0;
    public static final int FLAG_SNEAKING = 1;
    public static final int FLAG_RIDING = 2;
    public static final int FLAG_SPRINTING = 3;
    public static final int FLAG_USING_ITEM = 4;
    public static final int FLAG_INVISIBLE = 5;
    public static final int FLAG_IN_AIR = 7;
    public static final int FLAG_NO_AI = 8;
    public static final int FLAG_BREATHING = 9;
    public static final int FLAG_SWIMMING = 20;
    public static final int FLAG_GLIDING = 23;

    public boolean flag(int ordinal) {
        return (this.flags & (1L << ordinal)) != 0;
    }

    /**
     * The tracked health attribute value, or -1 when unseen.
     */
    public float health() {
        return this.attr("minecraft:health");
    }

    public float maxHealth() {
        return this.attr("minecraft:health.max");
    }

    public float attr(String name) {
        Float value = this.attrs == null ? null : this.attrs.get(name);
        return value == null ? -1f : value;
    }
}
