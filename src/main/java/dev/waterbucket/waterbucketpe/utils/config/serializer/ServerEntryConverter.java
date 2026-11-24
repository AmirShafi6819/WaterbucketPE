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

package dev.waterbucket.waterbucketpe.utils.config.serializer;

import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfoType;
import dev.waterbucket.waterbucketpe.utils.config.ServerEntry;
import net.cubespace.Yamler.Config.ConfigSection;
import net.cubespace.Yamler.Config.Converter.Converter;
import net.cubespace.Yamler.Config.InternalConverter;

import java.lang.reflect.ParameterizedType;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class ServerEntryConverter implements Converter {

    private final InternalConverter internalConverter;

    public ServerEntryConverter(InternalConverter internalConverter) {
        this.internalConverter = internalConverter;
    }

    @Override
    public Object toConfig(Class<?> type, Object object, ParameterizedType parameterizedType) {
        // Object values: the optional disabled flag serializes as a YAML boolean
        Map<String, Object> map = new HashMap<>();
        ServerEntry serverEntry = (ServerEntry) object;
        map.put("address", serverEntry.getAddress().getHostString() + ":" + serverEntry.getAddress().getPort());
        if (serverEntry.getPublicAddress() != null && serverEntry.getPublicAddress() != serverEntry.getAddress()) {
            map.put("public_address", serverEntry.getPublicAddress().getHostString() + ":" + serverEntry.getPublicAddress().getPort());
        }
        if (serverEntry.getServerType() != null) {
            map.put("server_type", serverEntry.getServerType());
        }
        // Omitted when false so existing config files stay byte-identical until used
        if (serverEntry.isDisabled()) {
            map.put("disabled", true);
        }
        return map;
    }

    @Override
    public Object fromConfig(Class<?> type, Object object, ParameterizedType parameterizedType) throws Exception {
        if (object == null) {
            return null;
        }
        Converter inetConverter = internalConverter.getConverter(InetSocketAddress.class);
        InetSocketAddress address;
        InetSocketAddress publicAddress;
        String serverType;

        if (object instanceof ConfigSection section) {
            address = (InetSocketAddress) inetConverter.fromConfig(InetSocketAddress.class, section.get("address"), null);
            publicAddress = (InetSocketAddress) inetConverter.fromConfig(InetSocketAddress.class, section.get("public_address"), null);
            // The server type is a plain string: routing it through the address converter made
            // every entry with a non-empty server_type fail with "Invalid address 'bedrock'".
            serverType = (String) section.get("server_type");
            return new ServerEntry(section.get("name"), address, publicAddress, this.validateServerType(serverType),
                    this.readDisabled(section.get("disabled")));
        }

        if (object instanceof Map) {
            Map<String, Map<String, ?>> map = (Map<String, Map<String, ?>>) object;
            for (Map.Entry<String, Map<String, ?>> subMap : map.entrySet()) {
                address = (InetSocketAddress) inetConverter.fromConfig(InetSocketAddress.class, subMap.getValue().get("address"), null);
                publicAddress = (InetSocketAddress) inetConverter.fromConfig(InetSocketAddress.class, subMap.getValue().get("public_address"), null);
                serverType = (String) subMap.getValue().get("server_type");
                return new ServerEntry(subMap.getKey(), address, publicAddress, this.validateServerType(serverType),
                        this.readDisabled(subMap.getValue().get("disabled")));
            }
        }
        throw new IllegalArgumentException("ServerInfoConverter#fromConfig cannot parse obj: " + object.getClass().getName());
    }

    /**
     * Tolerant reader for the optional disabled flag: YAML may deliver it as Boolean or as a
     * string ("true"/"false") from hand-edited files. Absent = enabled.
     */
    private boolean readDisabled(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }

    private String validateServerType(String serverType) {
        if (serverType == null || serverType.isEmpty()) {
            return ServerInfoType.BEDROCK.getIdentifier();
        }
        return serverType;
    }

    @Override
    public boolean supports(Class<?> type) {
        return ServerEntry.class.isAssignableFrom(type);
    }
}
