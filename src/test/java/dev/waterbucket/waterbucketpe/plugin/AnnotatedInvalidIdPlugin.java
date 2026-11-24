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
 * Test fixture: an annotated plugin whose id violates the plugin name rules - the descriptor
 * scan must reject it instead of creating a folder outside the plugins directory.
 */
@PluginDescriptor(id = "../evil-dummy")
public class AnnotatedInvalidIdPlugin extends Plugin {

    @Override
    public void onEnable() {
    }
}
