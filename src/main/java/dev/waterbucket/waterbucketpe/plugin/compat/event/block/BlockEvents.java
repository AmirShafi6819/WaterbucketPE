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

package dev.waterbucket.waterbucketpe.plugin.compat.event.block;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos;
import dev.waterbucket.waterbucketpe.plugin.compat.world.ItemView;

/**
 * Block events. The action events (break, place, start break, sign edit) are
 * raised before the packet reaches the downstream server: cancelling drops
 * the action, so the change genuinely never happens.
 */
public final class BlockEvents {

    private BlockEvents() {
    }

    /**
     * A shared base for player block actions: the acting player, target
     * position and the item in hand.
     */
    public abstract static class PlayerBlockAction extends Event implements CancellableEvent {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final BlockPos pos;
        private final ItemView item;
        private final int face;
        private boolean cancelled;

        protected PlayerBlockAction(Player player, String serverKey, int dim,
                                    BlockPos pos, ItemView item, int face) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.pos = pos;
            this.item = item;
            this.face = face;
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

        public BlockPos getBlockPos() {
            return this.pos;
        }

        public ItemView getItem() {
            return this.item;
        }

        public int getFace() {
            return this.face;
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
     * A player finished breaking a block. Cancel: the server never sees the
     * break, the block stays.
     */
    public static final class Break extends PlayerBlockAction {

        public Break(Player player, String serverKey, int dim, BlockPos pos, ItemView item, int face) {
            super(player, serverKey, dim, pos, item, face);
        }
    }

    /**
     * A player used an item on a block (placement or interaction attempt).
     * Cancel: the use never reaches the server.
     */
    public static final class Place extends PlayerBlockAction {

        public Place(Player player, String serverKey, int dim, BlockPos pos, ItemView item, int face) {
            super(player, serverKey, dim, pos, item, face);
        }
    }

    /**
     * A player started breaking a block (left click down).
     */
    public static final class StartBreak extends PlayerBlockAction {

        public StartBreak(Player player, String serverKey, int dim, BlockPos pos, ItemView item, int face) {
            super(player, serverKey, dim, pos, item, face);
        }
    }

    /**
     * A block changed somewhere in the world (server told a client). The old
     * name resolves from the live model when the chunk is loaded.
     */
    public static final class Update extends Event {

        private final String serverKey;
        private final int dim;
        private final BlockPos pos;
        private final int runtimeId;
        private final String newName;
        private final String oldName;
        private final int layer;

        public Update(String serverKey, int dim, BlockPos pos, int runtimeId,
                      String newName, String oldName, int layer) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.pos = pos;
            this.runtimeId = runtimeId;
            this.newName = newName;
            this.oldName = oldName;
            this.layer = layer;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public BlockPos getBlockPos() {
            return this.pos;
        }

        public int getRuntimeId() {
            return this.runtimeId;
        }

        public String getNewName() {
            return this.newName;
        }

        public String getOldName() {
            return this.oldName;
        }

        public int getLayer() {
            return this.layer;
        }
    }

    /**
     * A player edited a sign. Cancel: the edit is dropped and the sign keeps
     * its old text.
     */
    public static final class SignChange extends Event implements CancellableEvent {

        private final Player player;
        private final String serverKey;
        private final int dim;
        private final BlockPos pos;
        private final String[] lines;
        private boolean cancelled;

        public SignChange(Player player, String serverKey, int dim, BlockPos pos, String[] lines) {
            this.player = player;
            this.serverKey = serverKey;
            this.dim = dim;
            this.pos = pos;
            this.lines = lines;
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

        public BlockPos getBlockPos() {
            return this.pos;
        }

        public String[] getLines() {
            return this.lines;
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
