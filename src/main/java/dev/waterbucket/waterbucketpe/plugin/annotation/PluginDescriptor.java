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

package dev.waterbucket.waterbucketpe.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Velocity-style plugin descriptor, declared directly on the main class as an alternative to
 * shipping a {@code waterbucket.yml} / {@code plugin.yml} inside the plugin jar.
 * <p>
 * Rules:
 * <ul>
 *     <li>{@link #id()} is required and becomes the plugin's identity - the map key, the
 *     data-folder name and the {@code depends} lookup target</li>
 *     <li>{@link #name()} defaults to the id; the display name used in logs and the panel</li>
 *     <li>the annotated class IS the main class; it must still extend
 *     {@code dev.waterbucket.waterbucketpe.plugin.Plugin}</li>
 *     <li>a jar that also contains a YAML descriptor wins: the YAML is loaded and this
 *     annotation is ignored, so updating an existing plugin can not change behavior
 *     accidentally</li>
 *     <li>exactly one annotated class per jar is expected; extra annotated classes are
 *     reported and skipped</li>
 * </ul>
 * Example:
 * <pre>{@code
 * @PluginDescriptor(id = "greeter", name = "Greeter", version = "1.0.0", authors = {"Amir"})
 * public class GreeterPlugin extends Plugin {
 *     @Override
 *     public void onEnable() { }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginDescriptor {

    /**
     * Required, unique plugin identifier. May only contain letters, digits, spaces, dots,
     * dashes and underscores - the same rules YAML descriptors follow, because the id
     * becomes a directory name under {@code plugins/}.
     *
     * @return the unique plugin id
     */
    String id();

    /**
     * Optional human-readable display name; defaults to the id.
     *
     * @return the display name, or "" to use the id
     */
    String name() default "";

    /**
     * Plugin version string shown in logs, {@code /wbplugins} and the panel.
     *
     * @return the version, defaults to 1.0.0
     */
    String version() default "1.0.0";

    /**
     * Plugin authors, credited in the plugin list.
     *
     * @return the author names
     */
    /**
     * The plugin API generation the plugin targets. 1.6.0 is the current
     * generation; 1.0.0 declares the legacy surface and loads everywhere.
     */
    String apiVersion() default "1.0.0";

    String[] authors() default {};

    /**
     * Optional one-line description shown by the panel plugin list.
     *
     * @return the description, or ""
     */
    String description() default "";

    /**
     * Names of other plugins this plugin depends on. Dependencies are enabled first and the
     * plugin refuses to enable when one is missing - identical to the YAML {@code depends}
     * list.
     *
     * @return the dependency plugin ids
     */
    String[] dependencies() default {};
}
