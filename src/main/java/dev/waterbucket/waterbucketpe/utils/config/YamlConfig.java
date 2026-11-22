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

package dev.waterbucket.waterbucketpe.utils.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

public class YamlConfig extends Configuration {

    private final static DumperOptions dumperOptions;

    static {
        dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
    }

    public YamlConfig(String file) {
        super(file);
    }

    public YamlConfig(Path path) {
        super(path);
    }

    public YamlConfig(File saveFile) {
        super(saveFile);
    }

    public YamlConfig(File saveFile, InputStream inputStream) {
        super(saveFile, inputStream);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> deserialize(InputStream inputStream) {
        // SafeConstructor's loadAs(Map.class) demands an explicit root tag, so the plain load()
        // is used; a non-mapping root (or an empty document) yields null and Configuration.load
        // turns that into an empty, usable config.
        Object loaded = createYaml().load(inputStream);
        return loaded instanceof Map ? (Map<String, Object>) loaded : null;
    }

    @Override
    protected String serialize(Map<String, Object> values) {
        return createYaml().dump(values);
    }

    /**
     * SnakeYAML's {@code Yaml} is not thread-safe and this class is reachable from plugin
     * threads, so every call gets its own instance. The safe constructor additionally refuses
     * to instantiate arbitrary classes from {@code !!} tags in the loaded document.
     */
    private static Yaml createYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(dumperOptions), dumperOptions);
    }
}