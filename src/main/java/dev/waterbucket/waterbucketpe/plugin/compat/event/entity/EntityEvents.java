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

package dev.waterbucket.waterbucketpe.plugin.compat.event.entity;

import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.plugin.compat.world.EntityView;

/**
 * Entity events raised from real packets: spawn, despawn, movement, motion,
 * effects, health, metadata and teleports. All observations - the damage or
 * the spawn already happened server-side, so these are not cancellable; the
 * proxy cannot undo what a downstream server decided.
 */
public final class EntityEvents {

    private EntityEvents() {
    }

    /**
     * An entity became visible to a client (AddEntity / AddPlayer).
     */
    public static final class Spawn extends Event {

        private final String serverKey;
        private final EntityView entity;

        public Spawn(String serverKey, EntityView entity) {
            this.serverKey = serverKey;
            this.entity = entity;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public EntityView getEntity() {
            return this.entity;
        }
    }

    /**
     * An entity was removed (RemoveEntity).
     */
    public static final class Despawn extends Event {

        private final String serverKey;
        private final long eid;
        private final String type;

        public Despawn(String serverKey, long eid, String type) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.type = type;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public String getType() {
            return this.type;
        }
    }

    /**
     * An entity moved (movement snapshot).
     */
    public static final class Move extends Event {

        private final String serverKey;
        private final EntityView entity;
        private final boolean onGround;

        public Move(String serverKey, EntityView entity, boolean onGround) {
            this.serverKey = serverKey;
            this.entity = entity;
            this.onGround = onGround;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public EntityView getEntity() {
            return this.entity;
        }

        public boolean isOnGround() {
            return this.onGround;
        }
    }

    /**
     * An entity's motion vector updated.
     */
    public static final class Motion extends Event {

        private final String serverKey;
        private final long eid;
        private final float motX;
        private final float motY;
        private final float motZ;

        public Motion(String serverKey, long eid, float motX, float motY, float motZ) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.motX = motX;
            this.motY = motY;
            this.motZ = motZ;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public float getMotionX() {
            return this.motX;
        }

        public float getMotionY() {
            return this.motY;
        }

        public float getMotionZ() {
            return this.motZ;
        }
    }

    /**
     * Effect action ids.
     */
    public static final int EFFECT_ADD = 1;
    public static final int EFFECT_MODIFY = 2;
    public static final int EFFECT_REMOVE = 3;

    /**
     * A mob effect was added, modified or removed.
     */
    public static final class Effect extends Event {

        private final String serverKey;
        private final long eid;
        private final int action;
        private final int effectId;
        private final int amplifier;
        private final int duration;

        public Effect(String serverKey, long eid, int action, int effectId, int amplifier, int duration) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.action = action;
            this.effectId = effectId;
            this.amplifier = amplifier;
            this.duration = duration;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public int getAction() {
            return this.action;
        }

        public int getEffectId() {
            return this.effectId;
        }

        public int getAmplifier() {
            return this.amplifier;
        }

        public int getDuration() {
            return this.duration;
        }
    }

    /**
     * A health attribute updated. Damage and regain events below are derived
     * from the real delta between old and new.
     */
    public static final class HealthChange extends Event {

        private final String serverKey;
        private final long eid;
        private final float oldHealth;
        private final float newHealth;

        public HealthChange(String serverKey, long eid, float oldHealth, float newHealth) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.oldHealth = oldHealth;
            this.newHealth = newHealth;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public float getOldHealth() {
            return this.oldHealth;
        }

        public float getNewHealth() {
            return this.newHealth;
        }
    }

    /**
     * The proxy derived a real damage: the health attribute dropped.
     */
    public static final class Damage extends Event {

        private final String serverKey;
        private final long eid;
        private final float amount;
        private final float oldHealth;
        private final float newHealth;

        public Damage(String serverKey, long eid, float amount, float oldHealth, float newHealth) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.amount = amount;
            this.oldHealth = oldHealth;
            this.newHealth = newHealth;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public float getAmount() {
            return this.amount;
        }

        public float getOldHealth() {
            return this.oldHealth;
        }

        public float getNewHealth() {
            return this.newHealth;
        }
    }

    /**
     * The proxy derived a health gain: the health attribute rose.
     */
    public static final class RegainHealth extends Event {

        private final String serverKey;
        private final long eid;
        private final float amount;
        private final float oldHealth;
        private final float newHealth;

        public RegainHealth(String serverKey, long eid, float amount, float oldHealth, float newHealth) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.amount = amount;
            this.oldHealth = oldHealth;
            this.newHealth = newHealth;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public float getAmount() {
            return this.amount;
        }

        public float getOldHealth() {
            return this.oldHealth;
        }

        public float getNewHealth() {
            return this.newHealth;
        }
    }

    /**
     * Entity metadata flags updated (on fire, sneaking, invisible...).
     */
    public static final class DataUpdate extends Event {

        private final String serverKey;
        private final long eid;
        private final long flags;

        public DataUpdate(String serverKey, long eid, long flags) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.flags = flags;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public long getFlags() {
            return this.flags;
        }
    }

    /**
     * An entity teleported (teleport-flagged movement snapshot).
     */
    public static final class Teleport extends Event {

        private final String serverKey;
        private final long eid;
        private final float x;
        private final float y;
        private final float z;

        public Teleport(String serverKey, long eid, float x, float y, float z) {
            this.serverKey = serverKey;
            this.eid = eid;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public long getEntityId() {
            return this.eid;
        }

        public float getX() {
            return this.x;
        }

        public float getY() {
            return this.y;
        }

        public float getZ() {
            return this.z;
        }
    }
}
