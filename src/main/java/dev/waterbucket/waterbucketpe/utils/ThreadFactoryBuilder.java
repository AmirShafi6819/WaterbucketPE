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

package dev.waterbucket.waterbucketpe.utils;

import lombok.Builder;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Builder
public final class ThreadFactoryBuilder implements ThreadFactory {
    private static final ThreadFactory backingFactory = Executors.defaultThreadFactory();

    private final AtomicInteger count = new AtomicInteger(0);
    /**
     * Explicit default (false, matching the previous undeclared-field behavior) so builders that
     * never call {@code .daemon(...)} keep producing non-daemon threads.
     */
    @Builder.Default
    private final boolean daemon = false;
    private final String format;
    @Builder.Default
    private final int priority = Thread.currentThread().getPriority();
    private final Thread.UncaughtExceptionHandler exceptionHandler;

    private static String format(String format, int count) {
        return String.format(Locale.ROOT, format, count);
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = backingFactory.newThread(runnable);

        if (format != null) {
            thread.setName(format(format, count.getAndIncrement()));
        }

        thread.setDaemon(daemon);
        thread.setPriority(priority);

        if (exceptionHandler != null) {
            thread.setUncaughtExceptionHandler(exceptionHandler);
        }
        return thread;
    }
}