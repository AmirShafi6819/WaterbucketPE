/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.protocol.rewrite;

import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.Getter;
import lombok.Setter;

@Getter
public class RewriteMaps {

    private final EntityTracker entityTracker;
    private final EntityMap entityMap;
    /**
     * Set during StartGame handling on the joining downstream's event loop and read by
     * LevelChunkPacket rewrites on whichever downstream loop produced the chunk, so the
     * reference handoff must be memory-visible.
     */
    @Setter
    private volatile BlockMap blockMap;

    public RewriteMaps(ProxiedPlayer player) {
        this.entityTracker = new EntityTracker(player);
        this.entityMap = new EntityMap(player);
    }

}
