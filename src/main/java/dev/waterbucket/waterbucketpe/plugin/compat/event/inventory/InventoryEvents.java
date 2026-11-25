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

package dev.waterbucket.waterbucketpe.plugin.compat.event.inventory;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos;
import dev.waterbucket.waterbucketpe.plugin.compat.world.ItemView;

/**
 * Inventory events: windows opening and closing, slot updates, crafting
 * requests and generic transactions. Craft and transaction events are
 * cancellable before the server processes them; a cancelled craft is answered
 * to the client with a proper rejection.
 */
public final class InventoryEvents {

    private InventoryEvents() {
    }

    /**
     * A container window opened for a player (chest, furnace, ...).
     */
    public static final class Open extends Event {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final int windowId;
        private final String typeName;
        private final BlockPos pos;

        public Open(Player player, String serverKey, int dim, int windowId, String typeName, BlockPos pos) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.windowId = windowId;
            this.typeName = typeName;
            this.pos = pos;
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

        public int getWindowId() {
            return this.windowId;
        }

        public String getTypeName() {
            return this.typeName;
        }

        public BlockPos getBlockPos() {
            return this.pos;
        }
    }

    /**
     * A container window closed.
     */
    public static final class Close extends Event {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final int windowId;

        public Close(Player player, String serverKey, int dim, int windowId) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.windowId = windowId;
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

        public int getWindowId() {
            return this.windowId;
        }
    }

    /**
     * An inventory slot changed in the model (any window).
     */
    public static final class Update extends Event {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final int windowId;
        private final int slot;
        private final ItemView item;

        public Update(Player player, String serverKey, int dim, int windowId, int slot, ItemView item) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.windowId = windowId;
            this.slot = slot;
            this.item = item;
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

        public int getWindowId() {
            return this.windowId;
        }

        public int getSlot() {
            return this.slot;
        }

        public ItemView getItem() {
            return this.item;
        }
    }

    /**
     * A crafting request arrived. Cancelling answers the client with a
     * rejection through the stack request protocol - a real, clean cancel.
     */
    public static final class Craft extends Event implements CancellableEvent {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final int recipeNetId;
        private final String actions;
        private boolean cancelled;

        public Craft(Player player, String serverKey, int dim, int recipeNetId, String actions) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.recipeNetId = recipeNetId;
            this.actions = actions;
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

        public int getRecipeNetId() {
            return this.recipeNetId;
        }

        public String getActions() {
            return this.actions;
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
     * A generic inventory transaction (legacy transaction packet).
     */
    public static final class Transaction extends Event implements CancellableEvent {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final int transactionType;
        private final int actionType;
        private boolean cancelled;

        public Transaction(Player player, String serverKey, int dim, int transactionType, int actionType) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.transactionType = transactionType;
            this.actionType = actionType;
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

        public int getTransactionType() {
            return this.transactionType;
        }

        public int getActionType() {
            return this.actionType;
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
}
