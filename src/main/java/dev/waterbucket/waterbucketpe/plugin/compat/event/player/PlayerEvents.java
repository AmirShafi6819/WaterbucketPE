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

package dev.waterbucket.waterbucketpe.plugin.compat.event.player;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos;
import dev.waterbucket.waterbucketpe.plugin.compat.world.EntityView;
import dev.waterbucket.waterbucketpe.plugin.compat.world.ItemView;

/**
 * Player events beyond the classic join/quit/chat set: interactions with the
 * world, held items, toggles, animations, death, respawn, gamemode, dimension
 * changes, forms and entity targeting. Events raised from client-to-server
 * packets are cancellable (the packet is dropped); server confirmations are
 * observations.
 */
public final class PlayerEvents {

    private PlayerEvents() {
    }

    /**
     * Interact actions.
     */
    public static final int ACTION_LEFT_CLICK_BLOCK = 0;
    public static final int ACTION_RIGHT_CLICK_BLOCK = 1;

    /**
     * Base of the player events that carry a compatibility player wrapper.
     */
    public abstract static class PlayerEventBase extends Event {

        private final Player player;
        private final String serverKey;
        private final int dim;

        protected PlayerEventBase(Player player, String serverKey, int dim) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
        }

        public Player getPlayer() {
            return this.player;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }
    }

    /**
     * A player interacted with a block (left or right click). Cancelling
     * drops the interaction before the server sees it.
     */
    public static final class Interact extends PlayerEventBase implements CancellableEvent {

        private final BlockPos pos;
        private final int face;
        private final ItemView item;
        private final int action;
        private boolean cancelled;

        public Interact(Player player, String serverKey, int dim,
                        BlockPos pos, int face, ItemView item, int action) {
            super(player, serverKey, dim);
            this.pos = pos;
            this.face = face;
            this.item = item;
            this.action = action;
        }

        public BlockPos getBlockPos() {
            return this.pos;
        }

        public int getFace() {
            return this.face;
        }

        public ItemView getItem() {
            return this.item;
        }

        public int getAction() {
            return this.action;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player switched the held hotbar slot (server-confirmed).
     */
    public static final class ItemHeld extends PlayerEventBase {

        private final ItemView item;
        private final int hotbarSlot;
        private final int inventorySlot;

        public ItemHeld(Player player, String serverKey, int dim, ItemView item, int hotbarSlot, int inventorySlot) {
            super(player, serverKey, dim);
            this.item = item;
            this.hotbarSlot = hotbarSlot;
            this.inventorySlot = inventorySlot;
        }

        public ItemView getItem() {
            return this.item;
        }

        public int getHotbarSlot() {
            return this.hotbarSlot;
        }

        public int getInventorySlot() {
            return this.inventorySlot;
        }
    }

    /**
     * A player dropped an item. Cancelling drops the packet so the item never
     * leaves the inventory.
     */
    public static final class DropItem extends PlayerEventBase implements CancellableEvent {

        private final ItemView item;
        private final int count;
        private boolean cancelled;

        public DropItem(Player player, String serverKey, int dim, ItemView item, int count) {
            super(player, serverKey, dim);
            this.item = item;
            this.count = count;
        }

        public ItemView getItem() {
            return this.item;
        }

        public int getCount() {
            return this.count;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player toggled sneaking.
     */
    public static final class ToggleSneak extends PlayerEventBase {

        private final boolean sneaking;

        public ToggleSneak(Player player, String serverKey, int dim, boolean sneaking) {
            super(player, serverKey, dim);
            this.sneaking = sneaking;
        }

        public boolean isSneaking() {
            return this.sneaking;
        }
    }

    /**
     * A player toggled sprinting.
     */
    public static final class ToggleSprint extends PlayerEventBase {

        private final boolean sprinting;

        public ToggleSprint(Player player, String serverKey, int dim, boolean sprinting) {
            super(player, serverKey, dim);
            this.sprinting = sprinting;
        }

        public boolean isSprinting() {
            return this.sprinting;
        }
    }

    /**
     * A player played an animation (arm swing...). Cancelling stops the swing
     * from reaching the server and the other viewers.
     */
    public static final class Animation extends PlayerEventBase implements CancellableEvent {

        private final int action;
        private boolean cancelled;

        public Animation(Player player, String serverKey, int dim, int action) {
            super(player, serverKey, dim);
            this.action = action;
        }

        public int getAction() {
            return this.action;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player's health reached zero: a real, derived death moment.
     */
    public static final class Death extends PlayerEventBase {

        public Death(Player player, String serverKey, int dim) {
            super(player, serverKey, dim);
        }
    }

    /**
     * The server confirmed a respawn.
     */
    public static final class Respawn extends PlayerEventBase {

        private final float x;
        private final float y;
        private final float z;

        public Respawn(Player player, String serverKey, int dim, float x, float y, float z) {
            super(player, serverKey, dim);
            this.x = x;
            this.y = y;
            this.z = z;
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

    /**
     * The server changed a player's gamemode.
     */
    public static final class GameModeChange extends PlayerEventBase {

        private final int gamemode;

        public GameModeChange(Player player, String serverKey, int dim, int gamemode) {
            super(player, serverKey, dim);
            this.gamemode = gamemode;
        }

        public int getGamemode() {
            return this.gamemode;
        }
    }

    /**
     * A player moved to another dimension.
     */
    public static final class ChangeDimension extends PlayerEventBase {

        private final int fromDim;
        private final int toDim;

        public ChangeDimension(Player player, String serverKey, int dim, int fromDim, int toDim) {
            super(player, serverKey, dim);
            this.fromDim = fromDim;
            this.toDim = toDim;
        }

        public int getFromDimension() {
            return this.fromDim;
        }

        public int getToDimension() {
            return this.toDim;
        }
    }

    /**
     * A player answered a form. Cancelling drops the response: the server
     * never learns the form was answered.
     */
    public static final class FormRespond extends PlayerEventBase implements CancellableEvent {

        private final int formId;
        private final String formData;
        private boolean cancelled;

        public FormRespond(Player player, String serverKey, int dim, int formId, String formData) {
            super(player, serverKey, dim);
            this.formId = formId;
            this.formData = formData;
        }

        public int getFormId() {
            return this.formId;
        }

        public String getFormData() {
            return this.formData;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player interacted with an entity. Cancelling drops the interaction.
     */
    public static final class InteractEntity extends PlayerEventBase implements CancellableEvent {

        private final EntityView target;
        private boolean cancelled;

        public InteractEntity(Player player, String serverKey, int dim, EntityView target) {
            super(player, serverKey, dim);
            this.target = target;
        }

        public EntityView getTarget() {
            return this.target;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player hovered an entity (mouse over).
     */
    public static final class MouseOverEntity extends PlayerEventBase {

        private final EntityView target;

        public MouseOverEntity(Player player, String serverKey, int dim, EntityView target) {
            super(player, serverKey, dim);
            this.target = target;
        }

        public EntityView getTarget() {
            return this.target;
        }
    }

    /**
     * A player tried to sleep in a bed. Cancelling drops it.
     */
    public static final class BedEnter extends PlayerEventBase implements CancellableEvent {

        private final BlockPos pos;
        private boolean cancelled;

        public BedEnter(Player player, String serverKey, int dim, BlockPos pos) {
            super(player, serverKey, dim);
            this.pos = pos;
        }

        public BlockPos getBlockPos() {
            return this.pos;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }

    /**
     * A player stopped sleeping.
     */
    public static final class BedLeave extends PlayerEventBase {

        public BedLeave(Player player, String serverKey, int dim) {
            super(player, serverKey, dim);
        }
    }
}
