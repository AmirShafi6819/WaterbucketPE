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

package dev.waterbucket.waterbucketpe.utils.config.proxy;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.cubespace.Yamler.Config.Comment;
import net.cubespace.Yamler.Config.Comments;
import net.cubespace.Yamler.Config.Path;

/**
 * World View: the proxy watches the real worlds of every downstream server -
 * chunks, blocks, block entities, entities, players, inventories - by reading
 * the packets that describe them, and hands the data to the Go runtime which
 * keeps the live model. Plugins (Go and Java alike) then see what the server
 * actually streamed, not a guess.
 */
@Getter
@Setter
public class WorldViewSettings extends SettingsSection {

    @Path("enable")
    @Accessors(fluent = true)
    @Comments({
            "World View: the proxy learns the worlds of the downstream servers from the packets",
            "the clients receive - blocks, entities, players, inventories - and plugins can read",
            "and act on all of it. Enabled by default; the Go runtime ships inside the jar."
    })
    private boolean enable = true;

    @Path("go_runtime")
    @Accessors(fluent = true)
    @Comment("Start the embedded Go runtime sidecar (world model + Go plugins). When false, world queries answer unloaded and only Java-side events fire")
    private boolean goRuntime = true;

    @Path("retention_seconds")
    @Accessors(fluent = true)
    @Comment("How long unseen chunks stay in the world model before the janitor evicts them")
    private int retentionSeconds = 300;

    @Path("max_chunks_per_world")
    @Accessors(fluent = true)
    @Comment("Hard cap of resident chunks per world; the oldest ones are evicted first")
    private int maxChunksPerWorld = 4096;

    @Path("event_sync_timeout_ms")
    @Accessors(fluent = true)
    @Comment("Max wait for a cancellable event verdict from the Go runtime before the action is allowed through (fail-open)")
    private int eventSyncTimeoutMs = 8;

    @Path("go_plugins_dir")
    @Accessors(fluent = true)
    @Comment("Folder the Go runtime loads plugins from (each subfolder = one plugin with plugin.yml)")
    private String goPluginsDir = "plugins-go";

    @Path("go_log_to_console")
    @Accessors(fluent = true)
    @Comment("Also print Go runtime log lines to the proxy console (they always land in the log file)")
    private boolean goLogToConsole = false;
}
