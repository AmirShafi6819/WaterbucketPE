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

/**
 * Compiled-once PHP programs that drive the lifecycle of every {@link PhpPlugin}.
 * <p>
 * Quercus compiles these sources one time per plugin and they execute against the plugin's
 * persistent environment, where the plugin's own functions and globals live:
 * <ul>
 *     <li>{@link #BOOTSTRAP} creates the hook registry and wires the optional lifecycle
 *     functions ({@code on_startup}/{@code on_enable}/{@code on_disable}) into it, so a script
 *     only defines functions and the bridge handles dispatch.</li>
 *     <li>{@link #DISPATCH_HOOK} runs one registered hook by name with an argument array,
 *     which is how {@code callHook} reaches the script.</li>
 * </ul>
 * Everything else (event handlers, command executors, scheduled tasks) is invoked directly
 * through the marshaled PHP {@code Closure} object, so no registry round-trips per dispatch.
 */
final class PhpRuntime {

    /**
     * Hook registry + lifecycle wiring. Executed before the plugin's own script so the script
     * sees the registry helpers, and so functions the script defines are found afterwards by
     * {@code function_exists} (the scan below must therefore run AFTER the script - see
     * {@link PhpPlugin#loadScript}, which evaluates this file first and the script second, and
     * then re-runs the scan inside {@code LIFECYCLE_SCAN}).
     */
    static final String BOOTSTRAP = """
            $_WB_HOOKS = array();
            function wb_register_hook($name, $callable) {
                global $_WB_HOOKS;
                $_WB_HOOKS[$name] = $callable;
            }
            return true;
            """;

    /**
     * Wired after the plugin script has been evaluated, so {@code function_exists} sees the
     * functions the script actually defined. Absent hooks simply stay unregistered and
     * {@code callHook} becomes a no-op for them.
     */
    static final String LIFECYCLE_SCAN = """
            if (function_exists('on_startup')) { wb_register_hook('on_startup', 'on_startup'); }
            if (function_exists('on_enable')) { wb_register_hook('on_enable', 'on_enable'); }
            if (function_exists('on_disable')) { wb_register_hook('on_disable', 'on_disable'); }
            return true;
            """;

    /**
     * Dispatches one registered hook: reads the hook name and argument array from the
     * environment globals set by {@link PhpPlugin#callHook}.
     */
    static final String DISPATCH_HOOK = "return call_user_func_array($_WB_HOOKS[$_WB_NAME], $_WB_ARGS);";

    private PhpRuntime() {
    }
}
