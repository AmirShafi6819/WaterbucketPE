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

package dev.waterbucket.waterbucketpe.persianfix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.PersianFixSettings;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Packet-facing facade of the Persian display fix. Every method follows the same contract:
 * <ul>
 *     <li>the feature's master switch or the area toggle is off -> the input is returned as-is</li>
 *     <li>the text carries no Arabic-script character -> the very same String instance is
 *     returned after one O(n) scan, so Latin traffic pays a handful of comparisons and nothing
 *     else (no allocation, no re-encode of the packet below)</li>
 *     <li>the text already contains presentation forms -> it was shaped once already (our own
 *     chat echo coming back, or a downstream that ships the same fix server-side) and is
 *     passed through untouched, which is what makes fixing both directions safe</li>
 * </ul>
 * When a method does change the text, the packet handler re-encodes just that packet.
 */
@Log4j2
public final class PersianFixManager {

    /**
     * The bundled font pack teaches the client the Arabic presentation-form glyphs the shaped
     * text is made of. Offered automatically once at boot while the fix is enabled and the
     * proxy's own resource pack pipeline is active.
     */
    public static final String FONT_PACK_RESOURCE = "/persian/PersianFontPack.zip";
    public static final String FONT_PACK_NAME = "PersianFontPack.zip";

    /**
     * A sign holds at most four lines and every line wraps at 14 characters before fixing,
     * exactly like the original plugin's sign handler.
     */
    private static final int SIGN_LINE_LIMIT = 14;
    private static final int SIGN_MAX_LINES = 4;

    private PersianFixManager() {
    }

    // ---- player chat (client -> server) ----

    public static String fixChat(PersianFixSettings settings, String message) {
        if (!active(settings, settings.chat()) || message == null) {
            return message;
        }
        return fixOnce(message);
    }

    // ---- server-to-client text: system messages, popups, tips, whispers ----

    public static String fixMessage(PersianFixSettings settings, String message) {
        if (!active(settings, settings.messages()) || message == null) {
            return message;
        }
        return fixOnce(message);
    }

    // ---- titles and subtitles ----

    public static String fixTitle(PersianFixSettings settings, String text) {
        if (!active(settings, settings.title()) || text == null) {
            return text;
        }
        return fixOnce(text);
    }

    // ---- boss bar ----

    public static String fixBossBar(PersianFixSettings settings, String text) {
        if (!active(settings, settings.bossBar()) || text == null) {
            return text;
        }
        return fixOnce(text);
    }

    // ---- scoreboard display names and fake-player score entries ----

    public static String fixScoreboardText(PersianFixSettings settings, String text) {
        if (!active(settings, settings.scoreboard()) || text == null) {
            return text;
        }
        return fixOnce(text);
    }

    /**
     * Fixes every string value inside a form JSON payload (title, content, button text, label,
     * dropdown options, headers). Form JSON is parsed only when the payload actually contains
     * Arabic text, which the fast precheck finds with one scan before any parsing happens.
     *
     * @return the fixed JSON, or the original string instance when nothing needed changing
     */
    public static String fixFormJson(PersianFixSettings settings, String formData) {
        if (!active(settings, settings.forms()) || formData == null || formData.isEmpty()) {
            return formData;
        }
        if (!PersianTextEngine.containsArabic(formData, 0)
                || PersianTextEngine.containsPresentationForms(formData)) {
            return formData;
        }
        try {
            JsonElement root = JsonParser.parseString(formData);
            if (fixJsonStrings(root)) {
                return root.toString();
            }
            return formData;
        } catch (Exception e) {
            // A malformed payload must never break the form: pass it through untouched.
            log.debug("Persian Fix: could not parse form JSON ({} chars), forwarding as-is", formData.length(), e);
            return formData;
        }
    }

    private static boolean fixJsonStrings(JsonElement element) {
        boolean changed = false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String original = value.getAsString();
                    String fixed = fixOnce(original);
                    if (fixed != original) {
                        entry.setValue(new com.google.gson.JsonPrimitive(fixed));
                        changed = true;
                    }
                } else if (value.isJsonArray() || value.isJsonObject()) {
                    changed |= fixJsonStrings(value);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement value = array.get(i);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String original = value.getAsString();
                    String fixed = fixOnce(original);
                    if (fixed != original) {
                        array.set(i, new com.google.gson.JsonPrimitive(fixed));
                        changed = true;
                    }
                } else if (value.isJsonArray() || value.isJsonObject()) {
                    changed |= fixJsonStrings(value);
                }
            }
        }
        return changed;
    }

    // ---- signs ----

    /**
     * Fixes sign lines exactly like the original plugin's sign handler: every line longer than
     * 14 characters wraps onto the following line, the wrapped set is cut back to four lines
     * (empty lines keep their slot, like the original), and each line is fixed on its own.
     *
     * @return the fixed lines, or the original list instance when nothing changed at all
     */
    public static List<String> fixSignLines(PersianFixSettings settings, List<String> lines) {
        if (!active(settings, settings.sign()) || lines == null || lines.isEmpty()) {
            return lines;
        }

        List<String> wrapped = new ArrayList<>(SIGN_MAX_LINES);
        for (String line : lines) {
            if (line != null && line.codePointCount(0, line.length()) > SIGN_LINE_LIMIT) {
                // Wrap on codepoint boundaries so surrogate pairs never split.
                int[] cps = line.codePoints().toArray();
                for (int start = 0; start < cps.length && wrapped.size() < SIGN_MAX_LINES; start += SIGN_LINE_LIMIT) {
                    int end = Math.min(start + SIGN_LINE_LIMIT, cps.length);
                    wrapped.add(new String(cps, start, end - start));
                }
            } else if (wrapped.size() < SIGN_MAX_LINES) {
                wrapped.add(line == null ? "" : line);
            }
            if (wrapped.size() >= SIGN_MAX_LINES) {
                break;
            }
        }

        List<String> fixed = new ArrayList<>(SIGN_MAX_LINES);
        boolean changed = false;
        for (int i = 0; i < wrapped.size(); i++) {
            String out = fixOnce(wrapped.get(i));
            changed |= out != wrapped.get(i);
            fixed.add(out);
        }
        // Keep the protocol's fixed line count: modern sign Messages lists carry exactly four
        // entries, so a shorter wrapped set is padded back up with empty lines.
        while (fixed.size() < lines.size() && fixed.size() < SIGN_MAX_LINES) {
            fixed.add("");
            changed = true;
        }

        // Nothing wrapped, nothing fixed and the count untouched: hand back the original
        // instance so the packet handler skips the re-encode entirely.
        boolean identical = !changed && wrapped.size() == lines.size();
        if (identical) {
            for (int i = 0; i < wrapped.size(); i++) {
                if (!wrapped.get(i).equals(lines.get(i))) {
                    identical = false;
                    break;
                }
            }
        }
        return identical ? lines : fixed;
    }

    /**
     * Fixes the legacy whole-text sign format (one {@code Text} string with newline-separated
     * lines) with the same wrap-then-fix-per-line behavior.
     */
    public static String fixSignText(PersianFixSettings settings, String text) {
        if (!active(settings, settings.sign()) || text == null || text.isEmpty()) {
            return text;
        }
        if (!PersianTextEngine.containsArabic(text, 0)
                || PersianTextEngine.containsPresentationForms(text)) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<String> fixed = fixSignLines(settings, java.util.Arrays.asList(lines));
        return String.join("\n", fixed);
    }

    // ---- NBT of a sign block entity (client edits and server updates) ----

    /**
     * Rewrites the sign text inside a {@link BlockEntityDataPacket} NBT map. Modern versions
     * carry a {@code FrontText} / {@code BackText} compound with a {@code Messages} list of up
     * to four strings; older versions carry one {@code Text} string. Returns the original
     * instance when the NBT is not a sign or nothing needed changing, so the caller avoids a
     * needless re-encode.
     */
    public static NbtMap fixSignNbt(PersianFixSettings settings, NbtMap data) {
        if (!active(settings, settings.sign()) || data == null) {
            return data;
        }

        boolean modern = false;
        boolean legacy = false;
        NbtMapBuilder out = data.toBuilder();

        for (String side : new String[]{"FrontText", "BackText"}) {
            if (data.containsKey(side, NbtType.COMPOUND)) {
                NbtMap sideMap = data.getCompound(side);
                // A list entry is tagged NbtType.LIST on the compound level; its STRING element
                // type is what getList below reads with.
                if (sideMap.containsKey("Messages", NbtType.LIST)) {
                    List<String> messages = new ArrayList<>(sideMap.getList("Messages", NbtType.STRING));
                    List<String> fixed = fixSignLines(settings, messages);
                    if (fixed != messages) {
                        NbtMapBuilder sideOut = sideMap.toBuilder();
                        sideOut.put("Messages", new NbtList<>(NbtType.STRING, fixed));
                        out.putCompound(side, sideOut.build());
                        modern = true;
                    }
                }
            }
        }

        if (!modern && data.containsKey("Text", NbtType.STRING)) {
            String text = data.getString("Text");
            String fixed = fixSignText(settings, text);
            if (fixed != text) {
                out.put("Text", fixed);
                legacy = true;
            }
        }

        if (!modern && !legacy) {
            return data;
        }
        return out.build();
    }

    // ---- internals ----

    /**
     * The single entry into the engine. The two guards run before any real work: pure Latin
     * text returns the SAME String instance (one O(n) scan, zero allocation - the packet
     * handler then skips the re-encode), and text that already contains presentation forms is
     * passed through so nothing is ever fixed twice.
     */
    private static String fixOnce(String text) {
        if (text.isEmpty()
                || !PersianTextEngine.containsArabic(text, 0)
                || PersianTextEngine.containsPresentationForms(text)) {
            return text;
        }
        return PersianTextEngine.process(text);
    }

    private static boolean active(PersianFixSettings settings, boolean areaEnabled) {
        return settings != null && settings.enable() && areaEnabled;
    }

    /**
     * Extracts the bundled font pack into the data folder and registers it with the proxy's
     * pack pipeline, so joining clients receive the Persian glyph sheets exactly like the
     * original plugin offering its pack. Runs once at boot while the fix is enabled; a no-op
     * when the proxy's resource pack pipeline is disabled (downstream pack flow stays
     * untouched then, and a warning tells the operator what to flip).
     */
    public static void installFontPack(ProxyServer proxy) {
        PersianFixSettings settings;
        try {
            settings = proxy.getConfiguration().getPersianFixSettings();
        } catch (Exception e) {
            return;
        }
        if (settings == null || !settings.enable()) {
            return;
        }
        if (!proxy.getConfiguration().enableResourcePacks()) {
            log.warn("Persian Fix: enable_resource_packs is off, so the bundled Persian font pack cannot be "
                    + "offered to clients. Shaped text still travels, but clients without the glyphs may draw "
                    + "blank characters - set enable_resource_packs: true (the default) for full rendering.");
            return;
        }

        try {
            Path target = proxy.getDataPath().resolve("persian").resolve(FONT_PACK_NAME);
            Files.createDirectories(target.getParent());
            if (Files.notExists(target)) {
                try (InputStream in = PersianFixManager.class.getResourceAsStream(FONT_PACK_RESOURCE)) {
                    if (in == null) {
                        log.warn("Persian Fix: font pack {} missing from the jar, skipping the pack offer", FONT_PACK_RESOURCE);
                        return;
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            boolean registered = proxy.getPackManager().registerPack(
                    new dev.waterbucket.waterbucketpe.packs.types.ZipResourcePack(target));
            if (registered) {
                log.info("Persian Fix: the bundled Persian font pack is offered to joining clients ({})", target);
            } else {
                log.debug("Persian Fix: font pack already registered");
            }
        } catch (IOException e) {
            log.warn("Persian Fix: could not prepare the Persian font pack; shaped text still works, but the "
                    + "client may need the font from elsewhere", e);
        }
    }
}
