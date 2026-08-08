package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

//Tracks active sections, dispatches updates to the build system, everything related to rendering flows through here
public class RenderTracker {
    private static final int DEC_CHILDREN_BEFORE_PARENT_DELETE = 8;

    private RenderGenerationService renderGen;
    private final AbstractFarWorldRenderer renderer;
    private final LongSet[] sets;
    private final LongSet pendingIncDeletes = new LongOpenHashSet();
    private final Long2LongOpenHashMap pendingDecParents = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap pendingDecCounts = new Long2IntOpenHashMap();
    private final AtomicLong updateCount = new AtomicLong();
    private final AtomicLong activeUpdateCount = new AtomicLong();
    private final AtomicLong inactiveUpdateCount = new AtomicLong();
    private volatile long lastUpdateKey;
    private volatile long lastDiagnosticsTime;


    public RenderTracker(WorldEngine world, AbstractFarWorldRenderer renderer) {
        this.renderer = renderer;
        this.sets = new LongSet[1<<4];
        for (int i = 0; i < this.sets.length; i++) {
            this.sets[i] = new LongOpenHashSet();
        }
    }

    public void setRenderGen(RenderGenerationService renderGen) {
        this.renderGen = renderGen;
    }

    public static long mixStafford13(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    private LongSet getSet(long key) {
        return this.sets[(int) (mixStafford13(key) & (this.sets.length-1))];
    }

    private void put(long key) {
        var set = this.getSet(key);
        synchronized (set) {
            set.add(key);
        }
    }

    private void remove(long key) {
        var set = this.getSet(key);
        synchronized (set) {
            set.remove(key);
        }
    }

    private boolean contains(long key) {
        var set = this.getSet(key);
        synchronized (set) {
            return set.contains(key);
        }
    }

    //TODO: replace this:: with a class cached lambda ref (cause doing this:: still does a lambda allocation)

    //Adds a lvl 0 section into the world renderer
    public void addLvl0(int x, int y, int z) {
        this.put(WorldEngine.getWorldSectionId(0, x, y, z));
        this.renderGen.enqueueTask(0, x, y, z, this::shouldStillBuild);
    }

    //Removes a lvl 0 section from the world renderer
    public void remLvl0(int x, int y, int z) {
        this.remove(WorldEngine.getWorldSectionId(0, x, y, z));
        this.renderer.enqueueResult(new BuiltSection(WorldEngine.getWorldSectionId(0, x, y, z)));
        this.renderGen.removeTask(0, x, y, z);
    }

    //Increases from lvl-1 to lvl at the coordinates (which are in lvl space)
    public void inc(int lvl, int x, int y, int z) {
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1), (z<<1)));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1), (z<<1)+1));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1)+1, (z<<1)));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1)+1, (z<<1)+1));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1), (z<<1)));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1), (z<<1)+1));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1)));
        this.remove(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1)+1));
        long parentKey = WorldEngine.getWorldSectionId(lvl, x, y, z);
        this.put(parentKey);
        synchronized (this) {
            this.pendingIncDeletes.add(parentKey);
            this.clearPendingDec(parentKey);
        }

        //TODO: make a seperate object to hold the build data and link it with the location in a
        // concurrent hashmap or something, this is so that e.g. the build data position
        // can be updated

        //TODO: replace this:: with a class cached lambda ref (cause doing this:: still does a lambda allocation)
        this.renderGen.enqueueTask(lvl, x, y, z, this::shouldStillBuild);

        this.renderGen.removeTask(lvl-1, (x<<1), (y<<1), (z<<1));
        this.renderGen.removeTask(lvl-1, (x<<1), (y<<1), (z<<1)+1);
        this.renderGen.removeTask(lvl-1, (x<<1), (y<<1)+1, (z<<1));
        this.renderGen.removeTask(lvl-1, (x<<1), (y<<1)+1, (z<<1)+1);
        this.renderGen.removeTask(lvl-1, (x<<1)+1, (y<<1), (z<<1));
        this.renderGen.removeTask(lvl-1, (x<<1)+1, (y<<1), (z<<1)+1);
        this.renderGen.removeTask(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1));
        this.renderGen.removeTask(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1)+1);
    }

    //Decreases from lvl to lvl-1 at the coordinates (which are in lvl space)
    public void dec(int lvl, int x, int y, int z) {
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1), (z<<1)));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1), (z<<1)+1));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1)+1, (z<<1)));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1), (y<<1)+1, (z<<1)+1));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1), (z<<1)));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1), (z<<1)+1));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1)));
        this.put(WorldEngine.getWorldSectionId(lvl-1, (x<<1)+1, (y<<1)+1, (z<<1)+1));
        long parentKey = WorldEngine.getWorldSectionId(lvl, x, y, z);
        this.remove(parentKey);
        synchronized (this) {
            this.pendingIncDeletes.remove(parentKey);
            this.pendingDecCounts.put(parentKey, DEC_CHILDREN_BEFORE_PARENT_DELETE);
        }

        this.renderGen.removeTask(lvl, x, y, z);

        //TODO: replace this:: with a class cached lambda ref (cause doing this:: still does a lambda allocation)
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1), (y<<1), (z<<1));
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1), (y<<1), (z<<1)+1);
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1), (y<<1)+1, (z<<1));
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1), (y<<1)+1, (z<<1)+1);
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1)+1, (y<<1), (z<<1));
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1)+1, (y<<1), (z<<1)+1);
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1)+1, (y<<1)+1, (z<<1));
        this.enqueueDecChild(parentKey, lvl - 1, (x<<1)+1, (y<<1)+1, (z<<1)+1);
    }

    //Enqueues a renderTask for a section to cache the result
    public void addCache(int lvl, int x, int y, int z) {
        this.renderGen.markCache(lvl, x, y, z);
        this.renderGen.enqueueTask(lvl, x, y, z, ((lvl1, x1, y1, z1) -> true));//TODO: replace the true identity lambda with a callback check to the render cache
    }

    //Removes the position from the cache
    public void removeCache(int lvl, int x, int y, int z) {
        this.renderGen.unmarkCache(lvl, x, y, z);
    }

    public void remove(int lvl, int x, int y, int z) {
        this.remove(WorldEngine.getWorldSectionId(lvl, x, y, z));
        this.renderer.enqueueResult(new BuiltSection(WorldEngine.getWorldSectionId(lvl, x, y, z)));
    }

    public void add(int lvl, int x, int y, int z) {
        this.put(WorldEngine.getWorldSectionId(lvl, x, y, z));
        //TODO: replace this:: with a class cached lambda ref (cause doing this:: still does a lambda allocation)
        this.renderGen.enqueueTask(lvl, x, y, z, this::shouldStillBuild);
    }


    //Called by the world engine when a section gets dirtied
    public void sectionUpdated(WorldSection section) {
        this.updateCount.incrementAndGet();
        this.lastUpdateKey = section.key;
        // Invalidate cached meshes even when this LOD level is not currently active.
        // Otherwise a stale parent mesh can reappear when the camera later switches
        // to that level (for example, a tree removed from the world).
        this.renderGen.clearCache(section.lvl, section.x, section.y, section.z);
        this.renderGen.clearCache(section.lvl, section.x-1, section.y, section.z);
        this.renderGen.clearCache(section.lvl, section.x+1, section.y, section.z);
        this.renderGen.clearCache(section.lvl, section.x, section.y, section.z-1);
        this.renderGen.clearCache(section.lvl, section.x, section.y, section.z+1);

        if (this.contains(section.key)) {
            this.activeUpdateCount.incrementAndGet();
            // Neighbor meshes also depend on this section for face occlusion.
            this.renderGen.enqueueTask(section.lvl, section.x, section.y, section.z, this::shouldStillBuild);
            this.renderGen.enqueueTask(section.lvl, section.x-1, section.y, section.z, this::shouldStillBuild);
            this.renderGen.enqueueTask(section.lvl, section.x+1, section.y, section.z, this::shouldStillBuild);
            this.renderGen.enqueueTask(section.lvl, section.x, section.y, section.z-1, this::shouldStillBuild);
            this.renderGen.enqueueTask(section.lvl, section.x, section.y, section.z+1, this::shouldStillBuild);
        } else {
            this.inactiveUpdateCount.incrementAndGet();
        }
        this.writeUpdateDiagnostics();
        //this.renderGen.enqueueTask(section);
    }

    private void writeUpdateDiagnostics() {
        long now = System.currentTimeMillis();
        if (now - this.lastDiagnosticsTime < 2000) return;
        this.lastDiagnosticsTime = now;
        try {
            Path path = FabricLoader.getInstance().getGameDir().resolve("logs/voxy-render-updates.log");
            Files.createDirectories(path.getParent());
            String line = "updates=" + this.updateCount.get()
                    + " active=" + this.activeUpdateCount.get()
                    + " inactive=" + this.inactiveUpdateCount.get()
                    + " lastKey=" + this.lastUpdateKey + System.lineSeparator();
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    //called by the RenderGenerationService about built geometry, the RenderTracker checks if it can use the result (e.g. the LoD hasnt changed/still correct etc)
    // and dispatches it to the renderer
    // it also batch collects the geometry sections until all the geometry for an operation is collected, then it executes the operation, its removes flickering
    public void processBuildResult(BuiltSection section) {
        //Check that we still want the section
        if (this.contains(section.position)) {
            if (this.consumePendingIncDelete(section.position)) {
                this.enqueueChildDeletes(section.position);
            }
            var parentDelete = this.consumePendingDecChild(section.position);
            if (parentDelete != 0) {
                this.renderer.enqueueResult(new BuiltSection(parentDelete));
            }
            this.renderer.enqueueResult(section);
        } else {
            this.consumePendingDecChild(section.position);
            section.free();
        }
    }

    private boolean consumePendingIncDelete(long parentKey) {
        synchronized (this) {
            return this.pendingIncDeletes.remove(parentKey);
        }
    }

    private long consumePendingDecChild(long childKey) {
        synchronized (this) {
            long parentKey = this.pendingDecParents.remove(childKey);
            if (parentKey == 0) {
                return 0;
            }
            int remaining = this.pendingDecCounts.addTo(parentKey, -1) - 1;
            if (remaining <= 0) {
                this.clearPendingDec(parentKey);
                return parentKey;
            }
            return 0;
        }
    }

    private void clearPendingDec(long parentKey) {
        this.pendingDecCounts.remove(parentKey);
        int lvl = WorldEngine.getLevel(parentKey);
        if (lvl == 0) {
            return;
        }
        int childLvl = lvl - 1;
        int x = WorldEngine.getX(parentKey) << 1;
        int y = WorldEngine.getY(parentKey) << 1;
        int z = WorldEngine.getZ(parentKey) << 1;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    this.pendingDecParents.remove(WorldEngine.getWorldSectionId(childLvl, x + dx, y + dy, z + dz));
                }
            }
        }
    }

    private void enqueueDecChild(long parentKey, int lvl, int x, int y, int z) {
        long childKey = WorldEngine.getWorldSectionId(lvl, x, y, z);
        synchronized (this) {
            this.pendingDecParents.put(childKey, parentKey);
        }
        this.renderGen.enqueueTask(lvl, x, y, z, this::shouldStillBuild);
    }

    private void enqueueChildDeletes(long parentKey) {
        int lvl = WorldEngine.getLevel(parentKey);
        if (lvl == 0) {
            return;
        }
        int childLvl = lvl - 1;
        int x = WorldEngine.getX(parentKey) << 1;
        int y = WorldEngine.getY(parentKey) << 1;
        int z = WorldEngine.getZ(parentKey) << 1;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    this.renderer.enqueueResult(new BuiltSection(WorldEngine.getWorldSectionId(childLvl, x + dx, y + dy, z + dz)));
                }
            }
        }
    }

    public boolean shouldStillBuild(int lvl, int x, int y, int z) {
        return this.contains(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }
}
