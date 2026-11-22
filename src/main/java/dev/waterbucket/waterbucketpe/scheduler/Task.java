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

package dev.waterbucket.waterbucketpe.scheduler;

import dev.waterbucket.waterbucketpe.logger.MainLogger;
import lombok.Getter;

@Getter
public abstract class Task implements Runnable {

    private TaskHandler<Task> handler;

    public abstract void onRun(int currentTick);

    public abstract void onCancel();

    public void onError(Throwable error) {
        MainLogger.getLogger().error("Unable to complete task!", error);
    }

    @Override
    public void run() {
        this.onRun(this.handler.getLastRunTick());
    }

    public int getTaskId() {
        return this.handler == null ? -1 : this.handler.getTaskId();
    }

    public void cancel() {
        if (this.handler == null) {
            // A task that was never scheduled has nothing to cancel; the previous raw NPE
            // escaped into plugin code that could not tell what went wrong.
            return;
        }
        this.handler.cancel();
    }

    public void setHandler(TaskHandler<Task> handler) {
        if (this.handler != null) {
            throw new SecurityException("Can not change task handler!");
        }
        this.handler = handler;
    }
}
