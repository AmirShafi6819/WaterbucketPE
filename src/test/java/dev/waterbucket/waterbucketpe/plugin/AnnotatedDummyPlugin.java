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

package dev.waterbucket.waterbucketpe.plugin;

import dev.waterbucket.waterbucketpe.plugin.annotation.PluginDescriptor;

/**
 * Test fixture: a plugin declaring itself entirely through the Velocity-style
 * {@link PluginDescriptor} annotation, without a YAML descriptor in its jar.
 */
@PluginDescriptor(
        id = "annotation-dummy",
        name = "Annotation Dummy",
        version = "9.9.9",
        authors = {"Alice", "Bob"},
        description = "annotation descriptor fixture",
        dependencies = {"dep-a", "dep-b"}
)
public class AnnotatedDummyPlugin extends Plugin {

    @Override
    public void onEnable() {
    }
}
