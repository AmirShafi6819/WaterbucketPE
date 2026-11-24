/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distribution under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.utils.config.proxy;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.cubespace.Yamler.Config.Comment;
import net.cubespace.Yamler.Config.Path;

/**
 * Idle resource saver: after the configured idle time with zero players, the proxy enters
 * sleep mode - periodic downstream probes and sweeps are paused and a GC pass releases the
 * join caches - and wakes instantly on the first ping, query, login, panel request or
 * console command. The listener stays bound the whole time, so the next connection never
 * waits for a process start.
 */
@Getter
@Setter
public class SleepModeSettings extends SettingsSection {

    @Path("enable")
    @Accessors(fluent = true)
    @Comment("Enable sleep mode: with zero players the proxy trims memory and pauses periodic work, waking instantly on activity")
    private boolean enable = true;

    @Path("after_minutes")
    @Comment("Idle minutes with zero players before the proxy falls asleep (1-720)")
    private int afterMinutes = 5;
}
