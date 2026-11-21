/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.utils.types;

import dev.waterbucket.waterbucketpe.ProxyServer;
import lombok.Getter;

@Getter
public class TranslationContainer extends TextContainer {

    private String[] params;

    public TranslationContainer(String message) {
        super(message);
    }

    public TranslationContainer(String message, String... args) {
        super(message, args);
        this.params = args;
    }

    @Override
    protected String translate(String message, String... args) {
        return message;
    }

    public String getTranslated() {
        ProxyServer instance = ProxyServer.getInstance();
        // Null before boot (e.g. command settings built by tests or early plugin init): fall back
        // to the raw message instead of throwing, so class initialization never depends on boot order.
        return instance == null ? this.getMessage() : instance.translate(this);
    }

    @Override
    public String toString() {
        return this.getTranslated();
    }
}
