package com.github.lunatrius.schematica.world.schematic;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import com.github.lunatrius.schematica.util.SchematicLimits;

/** Bounded NBT reader for schematic files (gzip or plain), with allocation and depth limits. */
public final class LitematicaNBTReader {
    private LitematicaNBTReader() {}

    public static NBTTagCompound readFromFile(File file) throws IOException {
        return readFromStream(new FileInputStream(file));
    }

    public static NBTTagCompound readFromStream(InputStream source) throws IOException {
        try (BufferedInputStream stream = new BufferedInputStream(source)) {
            stream.mark(2);
            boolean gzip = stream.read() == 0x1f && stream.read() == 0x8b;
            stream.reset();
            try (DataInputStream input = new DataInputStream(gzip ? new GZIPInputStream(stream) : stream)) {
                Reader reader = new Reader(input);
                if (input.readByte() != 10) throw new IOException("NBT root must be a compound");
                reader.string();
                return (NBTTagCompound) reader.tag(10, 0);
            }
        }
    }

    /** The values of a long array tag (TAG_Long_Array, or the legacy int-pair packing), or null when absent. */
    public static long[] getLongArray(NBTTagCompound compound, String key) {
        NBTBase tag = compound.getTag(key);
        if (tag instanceof NBTTagLongArray) {
            try {
                // NBTBase.write is package-private: serialise a one-entry compound and read the array back out of it
                NBTTagCompound holder = new NBTTagCompound();
                holder.setTag("a", tag);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CompressedStreamTools.write(holder, new DataOutputStream(bytes));
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
                input.readByte();
                input.readUTF();
                input.readByte();
                input.readUTF();
                long[] values = new long[input.readInt()];
                for (int i = 0; i < values.length; i++) values[i] = input.readLong();
                return values;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (tag instanceof NBTTagIntArray) {
            int[] values = ((NBTTagIntArray) tag).getIntArray();
            if ((values.length & 1) != 0) throw new IllegalArgumentException("Odd packed long array length");
            long[] result = new long[values.length / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (values[i * 2] & 0xffffffffL) | ((long) values[i * 2 + 1] << 32);
            }
            return result;
        }
        return null;
    }

    private static final class Reader {
        private final DataInputStream input;
        private long remaining = SchematicLimits.readBudget();

        Reader(DataInputStream input) { this.input = input; }

        private void charge(long bytes) throws IOException {
            if (bytes < 0 || bytes > remaining) throw new IOException("NBT exceeds allocation limit");
            remaining -= bytes;
        }

        private String string() throws IOException {
            String value = input.readUTF();
            charge(40L + 2L * value.length());
            return value;
        }

        private int length(int bytesPerElement) throws IOException {
            int length = input.readInt();
            if (length < 0) throw new IOException("Negative NBT array/list length");
            charge(24L + (long) length * bytesPerElement);
            return length;
        }

        private long[] longArray() throws IOException {
            long[] values = new long[length(8)];
            for (int i = 0; i < values.length; i++) values[i] = input.readLong();
            return values;
        }

        private NBTBase tag(int type, int depth) throws IOException {
            if (depth > SchematicLimits.MAX_NBT_DEPTH) throw new IOException("NBT nesting is too deep");
            charge(64);
            switch (type) {
                case 1: return new NBTTagByte(input.readByte());
                case 2: return new NBTTagShort(input.readShort());
                case 3: return new NBTTagInt(input.readInt());
                case 4: return new NBTTagLong(input.readLong());
                case 5: return new NBTTagFloat(input.readFloat());
                case 6: return new NBTTagDouble(input.readDouble());
                case 7: {
                    byte[] values = new byte[length(1)];
                    input.readFully(values);
                    return new NBTTagByteArray(values);
                }
                case 8: return new NBTTagString(string());
                case 9: {
                    int elementType = input.readUnsignedByte();
                    int size = length(8);
                    if (elementType > 12 || (elementType == 0 && size != 0)) {
                        throw new IOException("Invalid NBT list type");
                    }
                    NBTTagList list = new NBTTagList();
                    for (int i = 0; i < size; i++) {
                        list.appendTag(tag(elementType, depth + 1));
                    }
                    return list;
                }
                case 10: {
                    NBTTagCompound compound = new NBTTagCompound();
                    int childType;
                    while ((childType = input.readUnsignedByte()) != 0) {
                        String name = string();
                        compound.setTag(name, tag(childType, depth + 1));
                    }
                    return compound;
                }
                case 12: return new NBTTagLongArray(longArray());
                case 11: {
                    int[] values = new int[length(4)];
                    for (int i = 0; i < values.length; i++) values[i] = input.readInt();
                    return new NBTTagIntArray(values);
                }
                default: throw new IOException("Unknown NBT tag type: " + type);
            }
        }
    }
}
