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

import net.cubespace.Yamler.Config.Converter.Converter;
import net.cubespace.Yamler.Config.InternalConverter;

import java.lang.reflect.ParameterizedType;
import java.net.InetSocketAddress;

public class InetSocketAddressConverter implements Converter {

    private final InternalConverter internalConverter;

    public InetSocketAddressConverter(InternalConverter internalConverter) {
        this.internalConverter = internalConverter;
    }

    @Override
    public Object toConfig(Class<?> type, Object object, ParameterizedType parameterizedType) {
        if (object == null) {
            return null;
        }
        InetSocketAddress address = (InetSocketAddress) object;
        return address.getHostString() + ":" + address.getPort();
    }

    @Override
    public Object fromConfig(Class<?> type, Object object, ParameterizedType parameterizedType) {
        if (object == null) {
            return null;
        }
        String string = (String) object;
        int separator = string.lastIndexOf(':');
        // "0.0.0.0" (no port) used to escape as StringIndexOutOfBounds and a non-numeric port
        // as a bare NumberFormatException, both surfacing as an opaque startup crash.
        if (separator <= 0) {
            throw new IllegalArgumentException("Invalid address '" + string + "': expected <host>:<port> (IPv6 literals need [host]:port)");
        }
        String address = string.substring(0, separator);
        int port;
        try {
            port = Integer.parseInt(string.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in address '" + string + "'", e);
        }
        return InetSocketAddress.createUnresolved(address, port);
    }

    @Override
    public boolean supports(Class<?> type) {
        return InetSocketAddress.class.isAssignableFrom(type);
    }
}
