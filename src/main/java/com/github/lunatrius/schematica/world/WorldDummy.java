package com.github.lunatrius.schematica.world;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import com.github.lunatrius.schematica.world.storage.SaveHandlerSchematic;

/** A world without chunks, used to instantiate tile entities and entities that are not in a real world. */
public class WorldDummy extends World {

    private static WorldDummy instance;

    public WorldDummy(ISaveHandler saveHandler, WorldInfo worldInfo, Profiler profiler) {
        super(saveHandler, worldInfo, new WorldProviderSurface(), profiler, false);
        // vanilla world classes link their provider to themselves; without it the provider has no world to read
        this.provider.setWorld(this);
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return false;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    public static synchronized WorldDummy instance() {
        if (instance == null) {
            final WorldSettings worldSettings = new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.FLAT);
            instance = new WorldDummy(new SaveHandlerSchematic(), new WorldInfo(worldSettings, "Schematica"), new Profiler());
        }

        return instance;
    }
}
