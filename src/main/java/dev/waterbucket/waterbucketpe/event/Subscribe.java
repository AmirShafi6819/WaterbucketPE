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

package dev.waterbucket.waterbucketpe.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event listener for annotation-based registration through
 * {@link EventManager#registerListener(dev.waterbucket.waterbucketpe.plugin.Plugin, Object)}.
 * <p>
 * The annotated method must be public and take exactly one parameter whose type is
 * {@link Event} or a subclass of it; it receives every instance of that event type the
 * proxy fires. This is the declaration-style alternative to the functional
 * {@code proxy.getEventManager().subscribe(EventClass.class, consumer)} API - both can be
 * mixed freely in the same plugin.
 * <p>
 * Example:
 * <pre>{@code
 * public class LoginListener {
 *
 *     @Subscribe(priority = EventPriority.HIGH, ignoreCancelled = true)
 *     public void onLogin(PlayerLoginEvent event) {
 *         event.setCancelled(true);
 *     }
 * }
 * }</pre>
 * Listeners registered through a {@code Plugin} are detached automatically when the plugin
 * is disabled; calling {@code unregisterListener(listenerObject)} manually is only needed
 * for per-instance listeners created at runtime.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /**
     * Execution priority of the handler, following the same ordering as the functional API:
     * {@link EventPriority#LOWEST} runs first, {@link EventPriority#HIGHEST} runs last and
     * should be used for observation only.
     *
     * @return the priority of this handler
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * When {@code true} and the handled event implements {@link CancellableEvent} and was
     * already cancelled by an earlier-priority handler, this method is skipped entirely -
     * the Velocity-style contract that cancelled events do not reach uninterested handlers.
     * Handlers at any priority always receive the event unless this flag is set.
     *
     * @return whether to skip already-cancelled cancellable events
     */
    boolean ignoreCancelled() default false;
}
