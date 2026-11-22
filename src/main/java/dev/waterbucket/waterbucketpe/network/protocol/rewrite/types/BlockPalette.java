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

package dev.waterbucket.waterbucketpe.network.protocol.rewrite.types;

import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import it.unimi.dsi.fastutil.objects.Object2ShortLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import org.apache.commons.lang3.tuple.Pair;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class used to map Block Palettes, as some server-softwares are randomizing them.
 * Prevents glitches with blocks.
 */
public class BlockPalette {

    /**
     * StartGame handling runs on every player's downstream event loop, so the cache is
     * read and written concurrently. The previous plain {@code Int2ObjectOpenHashMap} with a
     * contains-get-put sequence could corrupt its internal buckets when two loops inserted
     * the same or different palettes at once.
     */
    private static final Map<Integer, BlockPalette> paletteCache = new ConcurrentHashMap<>();
    /**
     * The cache is unbounded by design otherwise: every distinct palette hash is retained for
     * the proxy's lifetime. Real deployments see a handful of palettes (per game version and
     * server software), so this cap only protects against pathological servers that send a
     * fresh palette per player; past the cap new palettes are built per use, which is correct
     * but uncached.
     */
    private static final int PALETTE_CACHE_MAX_ENTRIES = 256;
    private final Object2ShortMap<BlockPair> entryToId = new Object2ShortLinkedOpenHashMap<>();
    private final Short2ObjectMap<BlockPair> idToEntry = new Short2ObjectLinkedOpenHashMap<>();

    public BlockPalette(NbtList<NbtMap> paletteData, ProtocolVersion protocol) {
        short id = 0;
        for (NbtMap item : paletteData) {
            final NbtMap block = item.getCompound("block");
            this.addEntry(id++, block.getString("name"), block.getCompound("states"));
        }
    }

    public static BlockPalette getPalette(NbtList<NbtMap> paletteData, ProtocolVersion protocol) {
        int hashId = paletteData.hashCode();
        BlockPalette cached = paletteCache.get(hashId);
        if (cached != null) {
            return cached;
        }

        BlockPalette palette = new BlockPalette(paletteData, protocol);
        if (paletteCache.size() < PALETTE_CACHE_MAX_ENTRIES) {
            paletteCache.putIfAbsent(hashId, palette);
        }
        return paletteCache.getOrDefault(hashId, palette);
    }

    public BlockPaletteRewrite createRewrite(BlockPalette upstreamPalette) {
        if (BlockPalette.this == upstreamPalette) {
            return BlockPaletteRewrite.BLOCK_EQUAL;
        }
        return new BlockPaletteRewrite(upstreamPalette, this);
    }

    private void addEntry(short id, String name, Object data) {
        final BlockPair pair = new BlockPair(name, data);
        this.entryToId.put(pair, id);
        this.idToEntry.put(id, pair);
    }

    public int getId(BlockPair entry) {
        return this.entryToId.getShort(entry) & 0xFFFF;
    }

    public BlockPair getEntry(int id) {
        return this.idToEntry.get((short) id);
    }

    public static final class BlockPair extends Pair<String, Object> {
        private final String key;
        private final Object data;
        private final int hash;

        public BlockPair(String name, Object data) {
            this.key = name;
            this.data = data;
            this.hash = super.hashCode();
        }

        public String getLeft() {
            return this.key;
        }

        public Object getRight() {
            return this.data;
        }

        @Override
        public Object setValue(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
