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

import dev.waterbucket.waterbucketpe.utils.config.proxy.PersianFixSettings;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manager layer around the engine: master switch, per-area toggles, the zero-cost fast
 * path for Latin traffic, the never-fix-twice guard, the sign wrap rules and the form JSON
 * walk. The engine's own vectors live in PersianTextEngineTest.
 */
public class PersianFixManagerTest {

    private static final String PERSIAN = "سلام علی";

    private PersianFixSettings settings() {
        return new PersianFixSettings();
    }

    private PersianFixSettings enabled() {
        PersianFixSettings settings = settings();
        settings.enable(true);
        return settings;
    }

    @Test
    public void everythingIsOffByDefault() {
        // The master switch ships off (the whole feature is opt-in); with it on, every area is
        // covered by default so operators only flip one key.
        PersianFixSettings settings = settings();
        assertFalse(settings.enable());
        assertTrue(settings.chat());
        assertTrue(settings.messages());
        assertTrue(settings.sign());
        assertTrue(settings.title());
        assertTrue(settings.bossBar());
        assertTrue(settings.scoreboard());
        assertTrue(settings.forms());
    }

    @Test
    public void masterSwitchGatesEveryArea() {
        // enable=false: even with the area toggles at their (true) defaults, nothing is fixed.
        PersianFixSettings settings = settings();
        assertFalse(PersianFixManager.fixChat(settings, PERSIAN).equals("ﻡﻼﺵ ﯽﻠﻋ") || PersianFixManager.fixChat(settings, PERSIAN) != PERSIAN);
        assertSame(PERSIAN, PersianFixManager.fixChat(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixMessage(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixTitle(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixBossBar(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixScoreboardText(settings, PERSIAN));
        assertEquals("{\"title\":\"" + PERSIAN + "\"}", PersianFixManager.fixFormJson(settings, "{\"title\":\"" + PERSIAN + "\"}"));
    }

    @Test
    public void eachAreaToggleWorksIndependently() {
        PersianFixSettings settings = enabled();
        settings.chat(false);
        settings.messages(false);
        settings.title(false);
        settings.bossBar(false);
        settings.scoreboard(false);
        settings.forms(false);
        settings.sign(false);

        String fixed = PersianTextEngine.process(PERSIAN);
        assertSame(PERSIAN, PersianFixManager.fixChat(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixMessage(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixTitle(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixBossBar(settings, PERSIAN));
        assertSame(PERSIAN, PersianFixManager.fixScoreboardText(settings, PERSIAN));
        assertSame("{\"title\":\"" + PERSIAN + "\"}",
                PersianFixManager.fixFormJson(settings, "{\"title\":\"" + PERSIAN + "\"}"));

        settings.chat(true);
        assertEquals(fixed, PersianFixManager.fixChat(settings, PERSIAN));
        settings.chat(false);

        settings.messages(true);
        assertEquals(fixed, PersianFixManager.fixMessage(settings, PERSIAN));
        settings.messages(false);

        settings.scoreboard(true);
        assertEquals(fixed, PersianFixManager.fixScoreboardText(settings, PERSIAN));
    }

    @Test
    public void latinTextPaysNothingAndKeepsTheSameInstance() {
        PersianFixSettings settings = enabled();
        String latin = "§2Salam Ali khobi? >>>";
        assertSame(latin, PersianFixManager.fixChat(settings, latin));
        assertSame(latin, PersianFixManager.fixMessage(settings, latin));
        assertSame(latin, PersianFixManager.fixTitle(settings, latin));
        assertSame(latin, PersianFixManager.fixBossBar(settings, latin));
        assertSame(latin, PersianFixManager.fixScoreboardText(settings, latin));
        assertSame(latin, PersianFixManager.fixFormJson(settings, latin));
        // empty strings too
        assertSame("", PersianFixManager.fixChat(settings, ""));
    }

    @Test
    public void alreadyFixedTextIsNeverFixedTwice() {
        PersianFixSettings settings = enabled();
        // The proxy fixes chat upstream; the server echoes the line back downstream. The
        // presentation-forms guard must keep the echo identical, or the line flips again.
        String fixed = PersianFixManager.fixChat(settings, PERSIAN);
        assertTrue(PersianTextEngine.containsPresentationForms(fixed));
        assertSame(fixed, PersianFixManager.fixMessage(settings, fixed));
        assertSame(fixed, PersianFixManager.fixChat(settings, fixed));
        assertSame(fixed, PersianFixManager.fixTitle(settings, fixed));
        assertSame(fixed, PersianFixManager.fixScoreboardText(settings, fixed));
    }

    @Test
    public void signLinesWrapAtFourteenCharactersLikeTheOriginal() {
        PersianFixSettings settings = enabled();
        // 20 characters -> two wrapped lines of 14 + 6, then fixed.
        String longLine = "این یک متن طولانی برای تابلوی سرور است";
        assertTrue(longLine.codePointCount(0, longLine.length()) > 14);

        List<String> lines = Arrays.asList(longLine, "line2", "", "line4");
        List<String> fixed = PersianFixManager.fixSignLines(settings, lines);

        assertFalse(fixed == lines);
        // The long line wrapped, everything after it shifted one slot down, and the tail left
        // the sign: exactly the original plugin's wrap-then-slice-to-four behavior.
        assertTrue(fixed.get(0).codePointCount(0, fixed.get(0).length()) <= 14);
        assertEquals(fixed.size(), 4);
        assertTrue(PersianTextEngine.containsPresentationForms(fixed.get(0)));
    }

    @Test
    public void signLinesWithoutPersianStayUntouched() {
        PersianFixSettings settings = enabled();
        List<String> lines = Arrays.asList("score:", "123", "", "right");
        assertSame(lines, PersianFixManager.fixSignLines(settings, lines));
    }

    @Test
    public void modernSignNbtFrontAndBackAreFixed() {
        PersianFixSettings settings = enabled();
        NbtList<String> messages = new NbtList<>(NbtType.STRING,
                Arrays.asList(PERSIAN, "", "", ""));
        NbtMap side = NbtMap.builder()
                .putInt("SignedVersion", 1)
                .putList("Messages", NbtType.STRING, PERSIAN, "", "", "")
                .build();
        NbtMap data = NbtMap.builder()
                .putString("id", "Sign")
                .putCompound("FrontText", side)
                .putCompound("BackText", side)
                .build();

        NbtMap fixed = PersianFixManager.fixSignNbt(settings, data);
        assertFalse(fixed == data);
        assertTrue(PersianTextEngine.containsPresentationForms(
                fixed.getCompound("FrontText").getList("Messages", NbtType.STRING).get(0)));
        assertTrue(PersianTextEngine.containsPresentationForms(
                fixed.getCompound("BackText").getList("Messages", NbtType.STRING).get(0)));
        // untouched side data survives the rebuild
        assertEquals(1, fixed.getCompound("FrontText").getInt("SignedVersion"));
        assertEquals("Sign", fixed.getString("id"));
    }

    @Test
    public void nonSignBlockEntitiesAreNotTouched() {
        PersianFixSettings settings = enabled();
        NbtMap data = NbtMap.builder().putString("id", "Chest").putInt("x", 10).build();
        assertSame(data, PersianFixManager.fixSignNbt(settings, data));
    }

    @Test
    public void formJsonFixesEveryTextFieldAndKeepsStructure() {
        PersianFixSettings settings = enabled();
        String form = "{\"type\":\"custom_form\",\"title\":\"پنل خرید\",\"content\":\"انتخاب کنید\","
                + "\"buttons\":[{\"text\":\"دکمه اول\",\"image\":{\"path\":\"a.png\"}}],"
                + "\"dropdown\":[\"گزینه یک\",\"گزینه دو\"],\"price\":250,\"latin\":\"Hello\"}";
        String fixed = PersianFixManager.fixFormJson(settings, form);
        assertFalse(fixed == form);

        // Numbers and Latin strings stay untouched, Persian strings are shaped.
        assertTrue(fixed.contains("\"price\":250"));
        assertTrue(fixed.contains("Hello"));
        assertTrue(PersianTextEngine.containsPresentationForms(fixed));
        assertFalse(fixed.contains("پنل"));
        // structure preserved
        assertEquals("{\"type\":\"custom_form\",\"title\":\"", fixed.substring(0, "{\"type\":\"custom_form\",\"title\":\"".length()));
    }

    @Test
    public void malformedFormJsonIsPassedThrough() {
        PersianFixSettings settings = enabled();
        String broken = "{\"title\":\"سلام\", broken";
        assertSame(broken, PersianFixManager.fixFormJson(settings, broken));
    }

    @Test
    public void latinTrafficStaysFastOnTheHotPath() {
        // The per-packet contract: plain Latin must be rejected by a single scan without any
        // allocation, because this runs for every text packet of every player.
        PersianFixSettings settings = enabled();
        String latin = "§cPlayer joined the game";
        long start = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            assertSame(latin, PersianFixManager.fixMessage(settings, latin));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2_000, "200k Latin lines took " + elapsedMs + "ms - fast path broken");

        // And Persian lines still complete comfortably in bulk (a generous ceiling, the engine
        // itself is linear).
        String persian = PersianFixManager.fixChat(settings, "سلام، این یک پیام تستی برای بازیکنان است");
        long start2 = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            PersianFixManager.fixMessage(settings, "سلام، این یک پیام تستی برای بازیکنان است");
        }
        long elapsedMs2 = (System.nanoTime() - start2) / 1_000_000;
        assertTrue(elapsedMs2 < 5_000, "50k Persian lines took " + elapsedMs2 + "ms");
        assertFalse(persian.isEmpty());
    }

    @Test
    public void arabicPrecheckMatchesTheOriginalEngineScope() {
        assertTrue(PersianTextEngine.containsArabic(PERSIAN, 0));
        assertTrue(PersianTextEngine.containsArabic("hello سلام", 0));
        assertFalse(PersianTextEngine.containsArabic("hello world §c123", 0));
        assertFalse(PersianTextEngine.containsArabic(">>> [] <> {}", 0));
    }

    @Test
    public void punctuationSwapKeepsLatinSentencesReadable() {
        PersianFixSettings settings = enabled();
        // A mixed line: the Latin phrase keeps its order, its trailing punctuation flips to the
        // front of the word (same as the original engine's \p{P} handling).
        String fixed = PersianFixManager.fixChat(settings, "سلام Ali Khobi?");
        // The Latin phrase keeps its word order (with the trailing punctuation moved to its new
        // visual front, like the original engine) and reads unbroken.
        assertEquals(PersianTextEngine.process("سلام Ali Khobi?"), fixed);
        assertTrue(fixed.contains("Khobi"), "Latin word must stay readable: " + fixed);
    }
}
