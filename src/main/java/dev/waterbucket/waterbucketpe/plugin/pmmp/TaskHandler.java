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

package dev.waterbucket.waterbucketpe.plugin.pmmp;

import lombok.Getter;

/**
 * The compatibility task handle: what the scheduler hands back, carrying its id and the
 * {@link #cancel()} switch.
 */
@Getter
public class TaskHandler {

    private final int taskId;
    private final dev.waterbucket.waterbucketpe.scheduler.TaskHandler<?> delegate;

    TaskHandler(int taskId, dev.waterbucket.waterbucketpe.scheduler.TaskHandler<?> delegate) {
        this.taskId = taskId;
        this.delegate = delegate;
    }

    /**
     * Cancels the task; a repeated task stops firing, a pending delayed task never runs.
     *
     * @return true when a delegate existed to cancel
     */
    public boolean cancel() {
        if (this.delegate == null) {
            return false;
        }
        this.delegate.cancel();
        return true;
    }
}
