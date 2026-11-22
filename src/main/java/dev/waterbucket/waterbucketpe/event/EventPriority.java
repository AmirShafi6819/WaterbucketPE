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

package dev.waterbucket.waterbucketpe.event;

/**
 * Represents the Priority of an event.
 * Default event priority, if not specific otherwise, is NORMAL.
 * LOWEST is called first,
 * HIGHEST is called last.
 */
public enum EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST;

    /**
     * values() clones its array on every call, and EventHandler iterates it for every event
     * dispatch on the network loops; iterate this shared copy instead.
     */
    public static final EventPriority[] VALUES = values();
}
