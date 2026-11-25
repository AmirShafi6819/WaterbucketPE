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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The complete vector set of the original Persian engine's own test suite
 * (AMGamer615/PersianChatFixer, tests/PersianTextEngineTest.php), replayed 1:1 against the
 * Java port: same input, same expected output, byte for byte.
 */
public class PersianTextEngineTest {
    @Test
    public void TestreversePersianWithEnglishAndPunctuation() {
        assertEquals("؟یبوخ (Ali یلع) مالس", PersianTextEngine.reversePersianText("سلام (علی Ali) خوبی؟"));
        assertEquals("؟یبوخ Ali Agha مالس", PersianTextEngine.reversePersianText("سلام Ali Agha خوبی؟"));
        assertEquals("؟یبوخ {یلع} مالس", PersianTextEngine.reversePersianText("سلام {علی} خوبی؟"));
        assertEquals("۱۲۳ مالس", PersianTextEngine.reversePersianText("سلام ۱۲۳"));
        assertEquals(">>> مالس", PersianTextEngine.reversePersianText(">>> سلام"));
        assertEquals("؟یبوخ [Ali] مالس", PersianTextEngine.reversePersianText("سلام [Ali] خوبی؟"));
        assertEquals("<!مبوخ> :تفگ", PersianTextEngine.reversePersianText("گفت: <خوبم!>"));
        assertEquals("؟یبوخ ،مالس", PersianTextEngine.reversePersianText("سلام، خوبی؟"));
    }

    @Test
    public void TestreverseEnglishOnly() {
        assertEquals("Salam Ali Khobi?", PersianTextEngine.reversePersianText("Salam Ali Khobi?"));
    }

    @Test
    public void TestcorrectpersiantextSimpleWords() {
        assertEquals("ﺳﻼﻡ", PersianTextEngine.correctPersianText("سلام"));
        assertEquals("ﺩﻧﯿﺎ", PersianTextEngine.correctPersianText("دنیا"));
        assertEquals("ﮐﺘﺎﺏ", PersianTextEngine.correctPersianText("کتاب"));
        assertEquals("ﻣﺪﺭﺳﻪ", PersianTextEngine.correctPersianText("مدرسه"));
    }

    @Test
    public void TestprocessSimpleWords() {
        assertEquals("ﻡﻼﺳ", PersianTextEngine.process("سلام"));
        assertEquals("ﺎﯿﻧﺩ", PersianTextEngine.process("دنیا"));
        assertEquals("ﺏﺎﺘﮐ", PersianTextEngine.process("کتاب"));
        assertEquals("ﻪﺳﺭﺪﻣ", PersianTextEngine.process("مدرسه"));
        assertEquals("ﻩﺩﺎﻔﺘﺳﺍ", PersianTextEngine.process("استفاده"));
        assertEquals("ﺩﺍﺪﻣ", PersianTextEngine.process("مداد"));
        assertEquals("ﻭﺭﺎﮐ", PersianTextEngine.process("کارو"));
    }

    @Test
    public void TestprocessMixedTextWithPunctuation() {
        assertEquals("!ﺎﯿﻧﺩ ،ﻡﻼﺳ", PersianTextEngine.process("سلام، دنیا!"));
        assertEquals("؟ﯽﺑﻮﺧ", PersianTextEngine.process("خوبی؟"));
        assertEquals(";ﺎﯿﻧﺩ :ﻡﻼﺳ", PersianTextEngine.process("سلام: دنیا;"));
    }

    @Test
    public void TestprocessEnglishOrMixedLanguage() {
        assertEquals("<salam>", PersianTextEngine.process("<salam>"));
        assertEquals("؟ﯽﺑﻮﺧ Ali ﻡﻼﺳ", PersianTextEngine.process("سلام Ali خوبی؟"));
        assertEquals("ﻡﻼﺳ Ali", PersianTextEngine.process("Ali سلام"));
        assertEquals("Book ﺏﺎﺘﮐ", PersianTextEngine.process("کتاب Book"));
        assertEquals("ﯼﺩﻼﯿﻣ 2025 ﻝﺎﺳ", PersianTextEngine.process("سال 2025 میلادی"));
        assertEquals("ﺪﯿﻨﮐ ﯼﺭﺍﺪﯾﺮﺧ ﺍﺭ ﺩﻮﺧ ﺯﺎﯿﻧ ﺩﺭﻮﻣ ﻞﯾﺎﺳﻭ /shop ﺭﻮﺘﺳﺩ ﺯﺍ ﻩﺩﺎﻔﺘﺳﺍ ﺎﺑ", PersianTextEngine.process("با استفاده از دستور /shop وسایل مورد نیاز خود را خریداری کنید"));
        assertEquals("ﺖﺴﺗ 123", PersianTextEngine.process("123 تست"));
        assertEquals("456 ﺖﺴﺗ", PersianTextEngine.process("تست 456"));
        assertEquals("!ﺎﯿﻧﺩ Ali ﻡﻼﺳ", PersianTextEngine.process("سلام Ali دنیا!"));
        assertEquals(".ﻡﺪﯾﺩ ﻭﺭ Ali ﺯﻭﺮﻣﺍ", PersianTextEngine.process("امروز Ali رو دیدم."));
        assertEquals("!ﻪﯿﻟﺎﻋ X2025 ﻝﺪﻣ Tesla ﻭﺭﺩﻮﺧ", PersianTextEngine.process("خودرو Tesla مدل X2025 عالیه!"));
    }

    @Test
    public void TestprocessLaLigature() {
        assertEquals("ﮎﻻ", PersianTextEngine.process("لاک"));
        assertEquals("ﺎﯿﻧﺩ ﻻ ﻡﻼﺳ", PersianTextEngine.process("سلام لا دنیا"));
        assertEquals("ﮎﻻ Ali", PersianTextEngine.process("Ali لاک"));
    }

    @Test
    public void TestprocessQuotesAndParentheses() {
        assertEquals("(Book) ﺏﺎﺘﮐ", PersianTextEngine.process("کتاب (Book)"));
        assertEquals("Ali \"ﻡﻼﺳ\"", PersianTextEngine.process("\"سلام\" Ali"));
        assertEquals("Ali 'ﻡﻼﺳ'", PersianTextEngine.process("'سلام' Ali"));
    }

    @Test
    public void TestprocessEnglishOnlyLowercase() {
        assertEquals("salam khobi?", PersianTextEngine.process("salam khobi?"));
    }

    @Test
    public void TestprocessComplexSentences() {
        assertEquals(".ﻡﺪﯾﺩ ﻭﺭ Ali ﺯﻭﺮﻣﺍ ؟ﯽﺑﻮﺧ .ﻡﻼﺳ", PersianTextEngine.process("سلام. خوبی؟ امروز Ali رو دیدم."));
    }

    @Test
    public void TestprocessColorize() {
        assertEquals("§csalam khobi?", PersianTextEngine.process("§csalam khobi?"));
        assertEquals("§cﻡﻼﺳ", PersianTextEngine.process("§cسلام"));
        assertEquals("§c?khobi §cﻡﻼﺳ", PersianTextEngine.process("§cسلام khobi?"));
        assertEquals("§c؟ﯽﺑﻮﺧ §2Ali §cﻡﻼﺳ", PersianTextEngine.process("§cسلام §2Ali §cخوبی؟"));
        assertEquals("§c؟ﯽﺑﻮﺧ §csalam", PersianTextEngine.process("§csalam خوبی؟"));
        assertEquals("§2؟ﯽﺑﻮﺧ §2Ali §cﻡﻼﺳ", PersianTextEngine.process("§cسلام §2Ali خوبی؟"));
        assertEquals("§6?khobi §6ﯽﻠﻋ §6salam §3؟ﯽﺑﻮﺧ §3ali §3ﻡﻼﺳ§3  §2؟ﯽﺑﻮﺧ §2ﯽﻠﻋ §2ﻡﻼﺳ §c?salam ali khobi", PersianTextEngine.process("§csalam ali khobi? §2سلام علی خوبی؟ §3 سلام ali خوبی؟ §6salam علی khobi?"));
    }

}
