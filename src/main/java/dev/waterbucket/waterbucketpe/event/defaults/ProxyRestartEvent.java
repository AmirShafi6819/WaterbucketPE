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

package dev.waterbucket.waterbucketpe.event.defaults;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.event.Event;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * Fired before a restart requested through the {@code /restart} command (or the panel console)
 * actually begins. Cancelling it aborts the restart: no player is kicked and the proxy keeps
 * running, so plugins can veto a restart during maintenance windows or defer it.
 * <p>
 * This event is synchronous: the restart sequence is scheduled only after every handler has
 * run, so a veto is always honored before anything visible happens.
 */
@Getter
@Setter
@RequiredArgsConstructor
public class ProxyRestartEvent extends Event implements CancellableEvent {

    private final String initiator;
    private boolean cancelled = false;
}
