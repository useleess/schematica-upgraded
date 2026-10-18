package com.github.lunatrius.schematica.world.schematic;

import java.util.Collection;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;

import com.github.lunatrius.schematica.util.SchematicTransform;

/**
 * The state a block gets when the schematic is rotated (X, Y, Z: 90 degrees as {@link SchematicTransform}) or mirrored
 * (x, y, z). Turning around the vertical axis and mirroring horizontally use the blocks' own rotate and mirror;
 * the other operations turn the directions and axes stored in the state, and flip the half of slabs, stairs and
 * trapdoors when mirrored vertically. A state the block cannot hold afterwards keeps its value.
 */
public final class BlockStateTransform {
    private BlockStateTransform() {}

    public static IBlockState transform(IBlockState state, char op) {
        switch (op) {
            case 'Y': return state.withRotation(Rotation.CLOCKWISE_90);
            case 'x': return state.withMirror(Mirror.FRONT_BACK);
            case 'z': return state.withMirror(Mirror.LEFT_RIGHT);
            default: return turned(state, op);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState turned(IBlockState state, char op) {
        for (IProperty property : state.getPropertyKeys()) {
            Comparable value = state.getValue(property);
            Comparable replacement = null;
            if (property instanceof PropertyDirection) {
                replacement = SchematicTransform.direction(op, (EnumFacing) value);
            } else if (property.getName().equals("axis")) {
                replacement = axis(op, property.getAllowedValues(), value);
            } else if (op == 'y' && property.getName().equals("half") && flippable(state)) {
                replacement = flip(property.getAllowedValues(), value);
            }
            if (replacement != null && replacement != value && property.getAllowedValues().contains(replacement)) {
                state = state.withProperty(property, replacement);
            }
        }
        return state;
    }

    /** The axis a log-like block lies on after a rotation about X or Z (a mirror leaves axes alone), found by name. */
    @SuppressWarnings("rawtypes")
    private static Comparable axis(char op, Collection<? extends Comparable> allowed, Comparable value) {
        String name = value instanceof IStringSerializable ? ((IStringSerializable) value).getName() : value.toString();
        String turned = name;
        if (op == 'X') turned = name.equals("y") ? "z" : name.equals("z") ? "y" : name;
        else if (op == 'Z') turned = name.equals("y") ? "x" : name.equals("x") ? "y" : name;
        for (Comparable candidate : allowed) {
            String candidateName = candidate instanceof IStringSerializable ? ((IStringSerializable) candidate).getName() : candidate.toString();
            if (candidateName.equals(turned)) return candidate;
        }
        return null;
    }

    private static boolean flippable(IBlockState state) {
        return state.getBlock() instanceof BlockSlab || state.getBlock() instanceof BlockStairs
            || state.getBlock() instanceof BlockTrapDoor;
    }

    /** The other half of a two-valued top/bottom property. */
    @SuppressWarnings("rawtypes")
    private static Comparable flip(Collection<? extends Comparable> allowed, Comparable value) {
        if (allowed.size() != 2) return null;
        for (Comparable candidate : allowed) {
            if (!candidate.equals(value)) return candidate;
        }
        return null;
    }
}
