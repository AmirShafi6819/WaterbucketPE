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

/**
 * Faithful Java port of the Persian display fixer used by the widely deployed PocketMine
 * plugin ecosystem (MIT, (c) 2025 AMGamer615, https://github.com/AMGamer615/PersianChatFixer).
 * <p>
 * The Bedrock font renderer draws Arabic-script text in logical order with disconnected
 * letters, so the fix runs two steps, exactly like the original engine:
 * <ol>
 *     <li>{@link #correctPersianText} shapes every Persian letter into its correct Arabic
 *     presentation form (isolated / initial / medial / final), including the lam-alef
 *     ligature, based on which neighbors the letter can join.</li>
 *     <li>{@link #reversePersianText} reorders the tokens into visual (right-to-left) order
 *     while keeping Latin words, numbers, bracketed groups, the {@code §} color prefixes and
 *     the leading {@code ><[]} markers readable, so mixed Persian/English lines do not break.</li>
 * </ol>
 * Every codepoint table, regex behavior and edge case below is transcribed 1:1 from the
 * original PHP engine (and its own test suite drives our tests), so text fixed by the plugin
 * and text fixed here is byte-for-byte identical.
 * <p>
 * All methods are static and allocation-light: the hot paths scan {@code int} codepoints,
 * precompiled tables live in static finals and pure Latin input never allocates.
 */
public final class PersianTextEngine {

    private PersianTextEngine() {
    }

    /*
     * The shape table: key codepoint -> {isolated, initial, medial, final} presentation forms.
     * Transcribed with explicit escapes from the original engine so the compiled table is
     * independent of the source file encoding.
     */
    private static final int[][] GLYPHS;
    private static final int GLYPH_KEY_MAX;

    private static final int SECTION_SIGN = '\u00A7';

    // ا آ د ذ ر ز ژ و - letters that do not join to the following letter
    private static final boolean[] NON_CONNECTORS = new boolean[0x0700];

    // Lam-alef ligatures: isolated ﻻ, connected ﻼ
    private static final int LAM_ALEF_ISOLATED = '\uFEFB';
    private static final int LAM_ALEF_CONNECTED = '\uFEFC';

    /** Lookup table for the original {@code [A-Za-z0-9]} core check - O(1) per character. */
    private static final boolean[] LATIN_CORE = buildLatinCoreTable();

    static {
        String[] table = {
                "\u0622\uFE81\uFE81\uFE82\uFE82", // آ
                "\u0627\uFE8D\uFE8D\uFE8E\uFE8E", // ا
                "\u0628\uFE8F\uFE91\uFE92\uFE90", // ب
                "\u067E\uFB56\uFB58\uFB59\uFB57", // پ
                "\u062A\uFE95\uFE97\uFE98\uFE96", // ت
                "\u062B\uFE99\uFE9B\uFE9C\uFE9A", // ث
                "\u062C\uFE9D\uFE9F\uFEA0\uFE9E", // ج
                "\u0686\uFB7A\uFB7C\uFB7D\uFB7B", // چ
                "\u062D\uFEA1\uFEA3\uFEA4\uFEA2", // ح
                "\u062E\uFEA5\uFEA7\uFEA8\uFEA6", // خ
                "\u062F\uFEA9\uFEA9\uFEAA\uFEAA", // د
                "\u0630\uFEAB\uFEAB\uFEAC\uFEAC", // ذ
                "\u0631\uFEAD\uFEAD\uFEAE\uFEAE", // ر
                "\u0632\uFEAF\uFEAF\uFEB0\uFEB0", // ز
                "\u0698\uFB8A\uFB8A\uFB8B\uFB8B", // ژ
                "\u0633\uFEB1\uFEB3\uFEB4\uFEB2", // س
                "\u0634\uFEB5\uFEB7\uFEB8\uFEB6", // ش
                "\u0635\uFEB9\uFEBB\uFEBC\uFEBA", // ص
                "\u0636\uFEBD\uFEBF\uFEC0\uFEBE", // ض
                "\u0637\uFEC1\uFEC3\uFEC4\uFEC2", // ط
                "\u0638\uFEC5\uFEC7\uFEC8\uFEC6", // ظ
                "\u0639\uFEC9\uFECB\uFECC\uFECA", // ع
                "\u063A\uFECD\uFECF\uFED0\uFECE", // غ
                "\u0641\uFED1\uFED3\uFED4\uFED2", // ف
                "\u0642\uFED5\uFED7\uFED8\uFED6", // ق
                "\u06A9\uFB8E\uFB90\uFB91\uFB8F", // ک
                "\u06AF\uFB92\uFB94\uFB95\uFB93", // گ
                "\u0644\uFEDD\uFEDF\uFEE0\uFEDE", // ل
                "\u0645\uFEE1\uFEE3\uFEE4\uFEE2", // م
                "\u0646\uFEE5\uFEE7\uFEE8\uFEE6", // ن
                "\u0648\uFEED\uFEED\uFEEE\uFEEE", // و
                "\u0647\uFEE9\uFEEB\uFEEC\uFEEA", // ه
                "\u06CC\uFBFC\uFBFE\uFBFF\uFBFD", // ی
                "\u0626\uFE89\uFE8B\uFE8C\uFE8A", // ئ
                "\u0621\uFE80\uFE80\uFE80\uFE80", // ء
        };
        GLYPHS = new int[0x0700][];
        int max = 0;
        for (String row : table) {
            int key = row.charAt(0);
            GLYPHS[key] = new int[]{
                    row.codePointAt(1), row.codePointAt(2), row.codePointAt(3), row.codePointAt(4)
            };
            if (key > max) {
                max = key;
            }
        }
        GLYPH_KEY_MAX = max;

        for (int c : new int[]{'\u0627', '\u0622', '\u062F', '\u0630', '\u0631', '\u0632', '\u0698', '\u0648'}) {
            NON_CONNECTORS[c] = true;
        }
    }

    private static boolean[] buildLatinCoreTable() {
        boolean[] table = new boolean[128];
        for (char c = 'A'; c <= 'Z'; c++) {
            table[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            table[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            table[c] = true;
        }
        return table;
    }

    /**
     * The full fix, exactly like the original {@code PersianTextEngine::process}: shape first,
     * then reorder into visual order.
     *
     * @return the display-ready text, or the input unchanged when there is nothing to fix
     */
    public static String process(String text) {
        return reversePersianText(correctPersianText(text));
    }

    /**
     * Step 1 - shaping. Maps every Persian letter to the presentation form matching its join
     * context; the lam-alef pair collapses into its ligature. Non-Persian characters pass
     * through untouched.
     */
    public static String correctPersianText(String text) {
        int n = text.codePointCount(0, text.length());
        int[] chars = new int[n];
        for (int i = 0, cp = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            chars[cp++] = c;
            i += Character.charCount(c);
        }

        int[] result = new int[n + 4]; // a lam-alef pair shrinks the output, never grows it
        int out = 0;
        boolean skipNext = false;
        for (int i = 0; i < n; i++) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            int curr = chars[i];
            int prev = i > 0 ? chars[i - 1] : -1;
            int next = i < n - 1 ? chars[i + 1] : -1;

            if (curr == '\u0644' && next == '\u0627') { // ل + ا
                boolean connectsBefore = prev != -1 && isGlyph(prev) && !NON_CONNECTORS[prev];
                result[out++] = connectsBefore ? LAM_ALEF_CONNECTED : LAM_ALEF_ISOLATED;
                skipNext = true;
                continue;
            }

            if (!isGlyph(curr)) {
                result[out++] = curr;
                continue;
            }

            boolean connectsBefore = prev != -1 && isGlyph(prev) && !NON_CONNECTORS[prev];
            boolean connectsAfter = !NON_CONNECTORS[curr] && next != -1 && isGlyph(next);

            int form;
            if (connectsBefore) {
                form = connectsAfter ? 2 : 3;
            } else if (connectsAfter) {
                form = 1;
            } else {
                form = 0;
            }
            result[out++] = GLYPHS[curr][form];
        }
        return new String(result, 0, out);
    }

    /**
     * Step 2 - visual ordering. Reverses the token order of the line so the shaped text reads
     * right-to-left on the client, while Latin words keep their left-to-right order and move
     * together as whole phrases, bracketed groups fix their own inside recursively, {@code §x}
     * color codes stay attached to their token (inherited forward when a token lacks one) and
     * a leading {@code ><[]} marker stays at the start of the line.
     */
    public static String reversePersianText(String text) {
        int n = text.codePointCount(0, text.length());
        int[] cps = new int[n];
        for (int i = 0, cp = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            cps[cp++] = c;
            i += Character.charCount(c);
        }

        // Leading color codes: one or more "§ + any char" pairs at the very start.
        int pos = 0;
        while (pos + 1 < n && cps[pos] == SECTION_SIGN) {
            pos += 2;
        }
        int leadingColorEnd = pos; // 0 when there is no leading color pair
        String leadingColor = text.substring(0, charIndex(text, leadingColorEnd));

        // Leading marker run of > < [ ] plus the whitespace after it, kept at the front.
        int markerEnd = leadingColorEnd;
        while (markerEnd < n && isMarkerChar(cps[markerEnd])) {
            markerEnd++;
        }
        int symbolEnd = markerEnd;
        while (symbolEnd < n && isWhitespace(cps[symbolEnd])) {
            symbolEnd++;
        }
        String leadingSymbol = text.substring(charIndex(text, leadingColorEnd), charIndex(text, symbolEnd));

        int bodyStart = symbolEnd;
        if (!containsArabic(text, charIndex(text, bodyStart))) {
            // Nothing Persian in the body: the line is exactly as it arrived.
            return text;
        }

        // Tokenize the body exactly like the original regex: color-prefixed bracketed groups,
        // whitespace runs, and runs that are neither whitespace nor a delimiter. Unmatched
        // delimiter characters match no alternative and are dropped, like preg_match_all skips.
        String body = text.substring(charIndex(text, bodyStart));
        java.util.List<String> tokens = tokenize(body);

        // Merge pass: Latin/digit words joined by single spaces become one token, so a mixed
        // English phrase ("Ali Agha") keeps its word order as it moves across the line.
        java.util.List<String> merged = new java.util.ArrayList<>(tokens.size());
        int total = tokens.size();
        int i = 0;
        while (i < total) {
            String t = tokens.get(i);
            if (isWhitespaceOnly(t)) {
                merged.add(t);
                i++;
                continue;
            }
            if (isBracketWrapped(t)) {
                merged.add(t);
                i++;
                continue;
            }
            String core = stripColorPrefix(t);
            if (containsLatinCore(core)) {
                StringBuilder buf = new StringBuilder(t);
                int j = i + 1;
                while (j + 1 < total) {
                    if (!isWhitespaceOnly(tokens.get(j))) {
                        break;
                    }
                    if (!containsLatinCore(stripColorPrefix(tokens.get(j + 1)))) {
                        break;
                    }
                    buf.append(tokens.get(j)).append(tokens.get(j + 1));
                    j += 2;
                }
                merged.add(buf.toString());
                i = j;
                continue;
            }
            merged.add(t);
            i++;
        }

        // Color propagation: a token without its own §x prefix inherits the last one seen,
        // starting from the leading color codes stripped off the line start.
        String[] raws = merged.toArray(new String[0]);
        String[] applied = new String[raws.length];
        String[] cores = new String[raws.length];
        String lastPrefix = leadingColor;
        for (int k = 0; k < raws.length; k++) {
            String raw = raws[k];
            if (isWhitespaceOnly(raw)) {
                applied[k] = "";
                cores[k] = raw;
                continue;
            }
            String prefix = colorPrefixOf(raw);
            cores[k] = raw.substring(prefix.length());
            if (!prefix.isEmpty()) {
                lastPrefix = prefix;
            }
            applied[k] = prefix.isEmpty() ? lastPrefix : prefix;
        }

        // Reverse the token order and rebuild the line.
        StringBuilder out = new StringBuilder(body.length() + 8);
        for (int k = raws.length - 1; k >= 0; k--) {
            String core = cores[k];
            String color = applied[k];

            if (isWhitespaceOnly(raws[k])) {
                out.append(raws[k]);
                continue;
            }

            // Bracketed groups fix their inside recursively and keep their delimiters outside.
            if (startsWithOpener(core)) {
                int closer = closingFor(core.codePointAt(0));
                String inner = bracketInner(core);
                if (inner != null) {
                    out.append(color).append(core.charAt(0))
                            .append(reversePersianText(inner))
                            .append((char) closer);
                    continue;
                }
            }

            if (isMarkerRun(core)) {
                out.append(color).append(core);
                continue;
            }

            boolean arabic = containsArabic(core, 0);
            if (!arabic && containsLatinCore(core)) {
                // Move a trailing punctuation run in front of the Latin word, since the token
                // itself just flipped sides ("Hello!" must not become "!Hello" visually).
                out.append(color).append(moveTrailingPunctuationToFront(core));
                continue;
            }

            if (arabic && !isAllNumbers(core)) {
                out.append(color).append(reverseCodePoints(core));
                continue;
            }

            out.append(color).append(core);
        }

        return leadingSymbol + out;
    }

    // ---- tokenization (exact port of the original preg_match_all pattern) ----

    private static java.util.List<String> tokenize(String body) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        int n = body.codePointCount(0, body.length());
        int pos = 0;
        while (pos < n) {
            // (?:§.)* - any number of "§ + any char" pairs
            int start = pos;
            while (pos + 1 < n && codePointAt(body, pos) == SECTION_SIGN) {
                pos += 2;
            }
            if (pos >= n) {
                break; // a trailing lone "§" matches nothing and is dropped
            }
            int c = codePointAt(body, pos);
            String token = null;
            if (isOpener(c)) {
                int close = closingFor(c);
                int scan = pos + 1;
                while (scan < n && codePointAt(body, scan) != close) {
                    scan++;
                }
                if (scan < n) {
                    pos = scan + 1;
                    token = body.substring(charIndex(body, start), charIndex(body, pos));
                }
                // no closing delimiter: this alternative fails, fall through
            }
            if (token == null && isWhitespace(c)) {
                int scan = pos;
                while (scan < n && isWhitespace(codePointAt(body, scan))) {
                    scan++;
                }
                pos = scan;
                token = body.substring(charIndex(body, start), charIndex(body, pos));
            }
            if (token == null && !isWhitespace(c) && !isDelimiter(c)) {
                int scan = pos;
                while (scan < n && !isWhitespace(codePointAt(body, scan)) && !isDelimiter(codePointAt(body, scan))) {
                    scan++;
                }
                pos = scan;
                token = body.substring(charIndex(body, start), charIndex(body, pos));
            }
            if (token == null) {
                // No alternative matched (a stray opener without its closer): the scanner
                // advances one codepoint from where this attempt began, exactly like
                // preg_match_all stepping past unmatched input.
                pos = start + 1;
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isOpener(int c) {
        return c == '(' || c == '[' || c == '{' || c == '<';
    }

    private static boolean isDelimiter(int c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == '<' || c == '>';
    }

    private static boolean isMarkerChar(int c) {
        return c == '>' || c == '<' || c == '[' || c == ']';
    }

    private static int closingFor(int opener) {
        switch (opener) {
            case '(':
                return ')';
            case '[':
                return ']';
            case '{':
                return '}';
            case '<':
                return '>';
            default:
                return -1;
        }
    }

    // ---- small scanners ----

    private static boolean isGlyph(int c) {
        return c <= GLYPH_KEY_MAX && GLYPHS[c] != null;
    }

    /**
     * The cheap hot-path precheck: does the line contain any Arabic-script character at all?
     * Text without one (the overwhelming majority of lines on a busy proxy) skips the whole
     * engine with a single O(n) scan and no allocation.
     */
    public static boolean containsArabic(String text, int fromIndex) {
        for (int i = fromIndex; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (isArabicScript(c)) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }

    static boolean isArabicScript(int c) {
        return (c >= '\u0600' && c <= '\u06FF')
                || (c >= '\u0750' && c <= '\u077F')
                || (c >= '\u08A0' && c <= '\u08FF')
                || (c >= '\uFB50' && c <= '\uFDFF')
                || (c >= '\uFE70' && c <= '\uFEFF');
    }

    /**
     * Presentation forms (U+FB50-FDFF / U+FE70-FEFF) only occur in text that was already
     * shaped by this fix. The proxy checks this before running the engine so a line that was
     * fixed once (upstream chat echoed back downstream, or a server that fixes its own text)
     * is never reordered a second time.
     */
    public static boolean containsPresentationForms(String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if ((c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF')) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }

    private static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
                || Character.isWhitespace(c);
    }

    private static boolean isWhitespaceOnly(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); ) {
            int c = token.codePointAt(i);
            if (!isWhitespace(c)) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }

    private static boolean containsLatinCore(String core) {
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c < 128 && LATIN_CORE[c]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllNumbers(String core) {
        if (core.isEmpty()) {
            return false;
        }
        for (int i = 0; i < core.length(); ) {
            int c = core.codePointAt(i);
            int type = Character.getType(c);
            if (type != Character.DECIMAL_DIGIT_NUMBER && type != Character.LETTER_NUMBER
                    && type != Character.OTHER_NUMBER) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }

    private static boolean isMarkerRun(String core) {
        if (core.isEmpty()) {
            return false;
        }
        for (int i = 0; i < core.length(); i++) {
            if (!isMarkerChar(core.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Color prefix: zero or more "§ + any char" pairs at the token start. */
    private static String colorPrefixOf(String token) {
        int i = 0;
        while (i + 1 < token.length() && token.charAt(i) == SECTION_SIGN) {
            i += 2;
        }
        return token.substring(0, i);
    }

    private static String stripColorPrefix(String token) {
        String prefix = colorPrefixOf(token);
        return prefix.isEmpty() ? token : token.substring(prefix.length());
    }

    private static boolean startsWithOpener(String core) {
        if (core.isEmpty()) {
            return false;
        }
        int c = core.codePointAt(0);
        return c == '(' || c == '[' || c == '{' || c == '<';
    }

    /** The inside of a bracketed token, or null when the closer does not match the opener. */
    private static String bracketInner(String core) {
        int closer = closingFor(core.codePointAt(0));
        if (core.length() < 2 || core.charAt(core.length() - 1) != closer) {
            return null;
        }
        return core.substring(1, core.length() - 1);
    }

    /**
     * The merge-pass bracket check runs on the RAW token like the original regex
     * {@code ^[(\[{<].*[)\]}>]$}: a color-prefixed group does not qualify here (its first
     * character is a \u00A7), it only re-enters the recursive path in the output loop.
     */
    private static boolean isBracketWrapped(String token) {
        if (token.isEmpty() || !isOpener(token.codePointAt(0))) {
            return false;
        }
        return bracketInner(token) != null;
    }

    /** Trailing punctuation/symbol run moved in front of the word (the original \p{P}\p{S} swap). */
    static String moveTrailingPunctuationToFront(String core) {
        int cut = core.length();
        while (cut > 0) {
            int c = core.codePointBefore(cut);
            if (!isPunctOrSymbol(c)) {
                break;
            }
            cut -= Character.charCount(c);
        }
        if (cut == core.length() || cut == 0) {
            // No trailing punctuation, or the whole token is punctuation: nothing to move.
            return core;
        }
        return core.substring(cut) + core.substring(0, cut);
    }

    private static boolean isPunctOrSymbol(int c) {
        int type = Character.getType(c);
        switch (type) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    private static String reverseCodePoints(String text) {
        int n = text.codePointCount(0, text.length());
        int[] cps = new int[n];
        for (int i = 0, cp = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            cps[cp++] = c;
            i += Character.charCount(c);
        }
        for (int a = 0, b = n - 1; a < b; a++, b--) {
            int tmp = cps[a];
            cps[a] = cps[b];
            cps[b] = tmp;
        }
        return new String(cps, 0, n);
    }

    private static int codePointAt(String text, int cpIndex) {
        return text.codePointAt(charIndex(text, cpIndex));
    }

    /** Converts a codepoint index into the corresponding char index. */
    private static int charIndex(String text, int cpIndex) {
        if (cpIndex <= 0) {
            return 0;
        }
        int charIdx = 0;
        for (int seen = 0; seen < cpIndex && charIdx < text.length(); seen++) {
            charIdx += Character.charCount(text.codePointAt(charIdx));
        }
        return charIdx;
    }
}
