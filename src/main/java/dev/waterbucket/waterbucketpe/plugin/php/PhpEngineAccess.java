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

package dev.waterbucket.waterbucketpe.plugin.php;

import com.caucho.quercus.env.Env;
import com.caucho.quercus.program.QuercusProgram;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;

/**
 * Low-level access into Quercus' thread-local current-environment slot.
 * <p>
 * Quercus internals (ini lookups, error reporting, encoding) resolve the "current"
 * {@link Env} from a private static {@link ThreadLocal}, which is only populated while a
 * JSR-223 engine evaluation is on the stack. The PHP bridge executes compiled programs
 * directly against one persistent environment from arbitrary scheduler/event threads, so
 * every execution must install the environment for the duration of the call and remove it
 * afterwards - otherwise deep Quercus paths hit a null environment and crash.
 * <p>
 * The field handle is resolved once; if a future Quercus upgrade renames it, the failure is
 * reported at first use with a clear message instead of a bare {@link NoSuchFieldException}.
 */
final class PhpEngineAccess {

    private static final Field THREAD_ENV;

    static {
        Field found;
        try {
            found = Env.class.getDeclaredField("_threadEnv");
            found.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(
                    "Bundled Quercus runtime does not expose its thread environment slot (_threadEnv): " + e);
        }
        THREAD_ENV = found;
    }

    private PhpEngineAccess() {
    }

    /**
     * Runs {@code work} with {@code env} installed as the current environment of the calling
     * thread. The previous current environment (nested executions, e.g. an eval inside a
     * dispatch) is restored afterwards instead of being dropped, so an inner region's cleanup
     * cannot pull the environment out from under an outer one.
     */
    static <R> R execute(Env env, Callable<R> work) throws Throwable {
        @SuppressWarnings("unchecked")
        ThreadLocal<Env> threadEnv = (ThreadLocal<Env>) THREAD_ENV.get(null);
        Env previous = threadEnv.get();
        threadEnv.set(env);
        try {
            return work.call();
        } finally {
            if (previous != null) {
                threadEnv.set(previous);
            } else {
                threadEnv.remove();
            }
        }
    }

    /**
     * Void variant for programs whose result is discarded.
     */
    static void executeVoid(Env env, Runnable work) throws Throwable {
        @SuppressWarnings("unchecked")
        ThreadLocal<Env> threadEnv = (ThreadLocal<Env>) THREAD_ENV.get(null);
        Env previous = threadEnv.get();
        threadEnv.set(env);
        try {
            work.run();
        } finally {
            if (previous != null) {
                threadEnv.set(previous);
            } else {
                threadEnv.remove();
            }
        }
    }
}
