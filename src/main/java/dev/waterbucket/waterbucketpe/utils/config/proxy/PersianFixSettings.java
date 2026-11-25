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
 * Persian Fix: display-ready Persian (and every other Arabic-script language) on the client,
 * exactly like the well-known Persian server plugin family, but implemented once in the proxy
 * so it works with every downstream server software. The engine reshapes each letter into its
 * Arabic presentation form and reorders the line into visual right-to-left order while keeping
 * Latin words and numbers readable, and the bundled font pack teaches the client the shaped
 * glyphs. Everything is off by default; enabling {@code enable} activates the areas below.
 */
@Getter
@Setter
public class PersianFixSettings extends SettingsSection {

    @Path("enable")
    @Accessors(fluent = true)
    @Comments({
            "Persian Fix: makes Persian text display correctly (joined letters, right-to-left)",
            "everywhere the toggles below allow, on every downstream server software, because",
            "the fix runs inside the proxy on the packets themselves. Works for chat, signs and",
            "every other text surface the client renders. Disabled by default."
    })
    private boolean enable = false;

    @Path("chat")
    @Accessors(fluent = true)
    @Comment("Fix player chat before it travels to the downstream server and to other players")
    private boolean chat = true;

    @Path("messages")
    @Accessors(fluent = true)
    @Comment("Fix server-to-client text: system messages, popups, tips and whispers")
    private boolean messages = true;

    @Path("sign")
    @Accessors(fluent = true)
    @Comment("Fix sign text: lines longer than 14 characters wrap onto the next line (up to 4) before fixing, exactly like the original plugin")
    private boolean sign = true;

    @Path("title")
    @Accessors(fluent = true)
    @Comment("Fix titles and subtitles sent by the downstream server")
    private boolean title = true;

    @Path("boss_bar")
    @Accessors(fluent = true)
    @Comment("Fix boss bar titles sent by the downstream server")
    private boolean bossBar = true;

    @Path("scoreboard")
    @Accessors(fluent = true)
    @Comment("Fix scoreboard display names and the named (fake player) score entries")
    private boolean scoreboard = true;

    @Path("forms")
    @Accessors(fluent = true)
    @Comment("Fix every text field inside server forms: titles, content, buttons, labels, dropdown options and headers")
    private boolean forms = true;
}
