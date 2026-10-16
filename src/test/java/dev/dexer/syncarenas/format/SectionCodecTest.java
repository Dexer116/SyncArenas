package dev.dexer.syncarenas.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/**
 * Covers the encoding half of the codec. Decoding needs a live block registry
 * ({@code Bukkit#createBlockData}) and is therefore exercised in game, not here.
 */
class SectionCodecTest {

    /** A BlockData that only knows its own string form, which is all the encoder reads. */
    private static BlockData state(final String asString) {
        return (BlockData) Proxy.newProxyInstance(SectionCodecTest.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAsString" -> asString;
                case "toString" -> asString;
                case "hashCode" -> asString.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void uniformSectionsCarryNoIndices() throws IOException {
        final byte[] payload = SectionCodec.encode(List.of(state("minecraft:air")),
            new int[SectionPos.SECTION_VOLUME]);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals(1, in.readUnsignedShort());
            assertEquals("minecraft:air", in.readUTF());
            assertEquals(0, in.readUnsignedByte());
            assertEquals(0, in.available(), "a uniform section must not store a packed array");
        }
    }

    @Test
    void mixedSectionsRoundTripThroughTheWireFormat() throws IOException {
        final List<BlockData> palette = new ArrayList<>();
        final List<String> names = List.of("minecraft:air", "minecraft:obsidian",
            "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
            "minecraft:respawn_anchor[charges=0]", "minecraft:glass");
        for (final String name : names) {
            palette.add(state(name));
        }
        final int[] indices = new int[SectionPos.SECTION_VOLUME];
        final Random random = new Random(1234L);
        for (int i = 0; i < indices.length; i++) {
            indices[i] = random.nextInt(palette.size());
        }

        final byte[] payload = SectionCodec.encode(palette, indices);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals(names.size(), in.readUnsignedShort());
            for (final String name : names) {
                assertEquals(name, in.readUTF());
            }
            final int bits = in.readUnsignedByte();
            assertEquals(BitStorage.bitsFor(names.size()), bits);
            final long[] packed = new long[in.readInt()];
            assertEquals(BitStorage.packedLength(SectionPos.SECTION_VOLUME, bits), packed.length);
            for (int i = 0; i < packed.length; i++) {
                packed[i] = in.readLong();
            }
            assertEquals(0, in.available());
            for (int i = 0; i < indices.length; i++) {
                assertEquals(indices[i], BitStorage.get(packed, bits, i), "index " + i);
            }
        }
    }

    @Test
    void anEmptyPaletteIsRefused() {
        assertThrows(IOException.class,
            () -> SectionCodec.encode(List.of(), new int[SectionPos.SECTION_VOLUME]));
    }
}
