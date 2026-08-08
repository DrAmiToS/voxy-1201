package me.cortex.voxy.common.world.service;

import it.unimi.dsi.fastutil.Pair;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.WorldChunk;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class VoxelIngestService {
    private volatile boolean running = true;
    private final Thread[] workers;

    private record IngestTask(WorldChunk chunk, int sectionIndex, int sectionY,
                              Pair<ChunkNibbleArray, ChunkNibbleArray> lighting) {
        private long key() {
            return WorldEngine.getWorldSectionId(0, this.chunk.getPos().x, this.sectionY, this.chunk.getPos().z);
        }
    }

    private final ConcurrentLinkedDeque<IngestTask> ingestQueue = new ConcurrentLinkedDeque<>();
    // Multiple Sodium rebuild notifications can target the same section before a worker runs.
    // Keep only the newest snapshot for each section.
    private final ConcurrentHashMap<Long, IngestTask> pendingTasks = new ConcurrentHashMap<>();
    private final Semaphore ingestCounter = new Semaphore(0);
    private final AtomicLong enqueuedTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private volatile String lastTask = "none";
    private volatile long lastDiagnosticTime;

    private final WorldEngine world;
    public VoxelIngestService(WorldEngine world, int workers) {
        this.world = world;

        this.workers = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            var worker = new Thread(this::ingestWorker);
            worker.setDaemon(false);
            worker.setName("Ingest service #" + i);
            worker.start();
            this.workers[i]  = worker;
        }
    }

    private void ingestWorker() {
        while (this.running) {
            this.ingestCounter.acquireUninterruptibly();
            if (!this.running) break;
            try {
                var task = this.ingestQueue.pop();
                boolean claimed = this.pendingTasks.remove(task.key(), task);
                if (!claimed) {
                    var current = this.pendingTasks.remove(task.key());
                    if (current == null) continue;
                    task = current;
                }
                var chunk = task.chunk();
                var section = chunk.getSectionArray()[task.sectionIndex()];
                var lighting = task.lighting();
                int i = task.sectionY();
                this.lastTask = chunk.getPos().x + "," + i + "," + chunk.getPos().z;
                if (section.isEmpty() && lighting == null) {
                    this.world.insertUpdate(VoxelizedSection.createEmpty(chunk.getPos().x, i, chunk.getPos().z));
                } else {
                        VoxelizedSection csec = WorldConversionFactory.convert(
                                this.world.getMapper(),
                                section.getBlockStateContainer(),
                                section.getBiomeContainer(),
                                (x, y, z, state) -> {
                                    if (lighting == null || ((lighting.first() != null && lighting.first().isUninitialized())&&(lighting.second()!=null&&lighting.second().isUninitialized()))) {
                                        // Preserve the 1.20.1 port behavior until vanilla lighting is ready.
                                        return (byte) 0x0f;
                                    } else {
                                        //Lighting is a piece of shit cause its done per face
                                        int block = lighting.first()!=null?Math.min(15,lighting.first().get(x, y, z)):0;
                                        int sky = lighting.second()!=null?Math.min(15,lighting.second().get(x, y, z)):0;
                                        if (block<state.getLuminance()) {
                                            block = state.getLuminance();
                                        }
                                        sky = 15-sky;
                                        return (byte) (sky|(block<<4));
                                    }
                                },
                                chunk.getPos().x,
                                i,
                                chunk.getPos().z
                        );
                        WorldConversionFactory.mipSection(csec, this.world.getMapper());
                        this.world.insertUpdate(csec);
                }
                this.completedTasks.incrementAndGet();
            } catch (Exception e) {
                this.failedTasks.incrementAndGet();
                System.err.println(e);
                MinecraftClient.getInstance().executeSync(()->MinecraftClient.getInstance().player.sendMessage(Text.literal("Voxy ingester had an exception while executing please check logs and report error")));
            }
            this.writeDiagnostics();
        }
    }

    private static Pair<ChunkNibbleArray, ChunkNibbleArray> captureLighting(WorldChunk chunk, int sectionY) {
        var lightingProvider = chunk.getWorld().getLightingProvider();
        var blp = lightingProvider.get(LightType.BLOCK);
        var slp = lightingProvider.get(LightType.SKY);
        var pos = ChunkSectionPos.from(chunk.getPos(), sectionY);
        var bl = blp.getLightSection(pos);
        var sl = slp.getLightSection(pos);
        if (bl == null || bl.isUninitialized()) bl = null;
        else bl = bl.copy();
        if (sl == null || sl.isUninitialized()) sl = null;
        else sl = sl.copy();
        return bl == null && sl == null ? null : Pair.of(bl, sl);
    }

    public void enqueueIngest(WorldChunk chunk) {
        int i = chunk.getBottomSectionCoord();
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionArray().length; sectionIndex++, i++) {
            this.enqueueSection(chunk, sectionIndex, i);
        }
    }

    public void enqueueIngest(WorldChunk chunk, int sectionY) {
        int sectionIndex = sectionY - chunk.getBottomSectionCoord();
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionArray().length) return;
        this.enqueueSection(chunk, sectionIndex, sectionY);
    }

    private void enqueueSection(WorldChunk chunk, int sectionIndex, int sectionY) {
        var lighting = captureLighting(chunk, sectionY);
        var task = new IngestTask(chunk, sectionIndex, sectionY, lighting);
        if (this.pendingTasks.put(task.key(), task) == null) {
            this.ingestQueue.add(task);
            this.ingestCounter.release();
        }
        this.enqueuedTasks.incrementAndGet();
    }

    private void writeDiagnostics() {
        long now = System.currentTimeMillis();
        if (now - this.lastDiagnosticTime < 2000) return;
        this.lastDiagnosticTime = now;
        try {
            Path path = FabricLoader.getInstance().getGameDir().resolve("logs/voxy-ingest.log");
            Files.createDirectories(path.getParent());
            String line = "enqueued=" + this.enqueuedTasks.get()
                    + " completed=" + this.completedTasks.get()
                    + " failed=" + this.failedTasks.get()
                    + " pending=" + this.ingestCounter.availablePermits()
                    + " last=" + this.lastTask + System.lineSeparator();
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public int getTaskCount() {
        return this.ingestCounter.availablePermits();
    }

    public void shutdown() {
        boolean anyAlive = false;
        boolean allAlive = true;
        for (var worker : this.workers) {
            anyAlive |= worker.isAlive();
            allAlive &= worker.isAlive();
        }

        if (!anyAlive) {
            System.err.println("Ingest workers already dead on shutdown! this is very very bad, check log for errors from this thread");
            return;
        }
        if (!allAlive) {
            System.err.println("Some ingest workers already dead on shutdown! this is very very bad, check log for errors from this thread");
        }

        //Wait for the ingest to finish
        while (this.ingestCounter.availablePermits() != 0) {
            Thread.onSpinWait();
        }
        //Shutdown
        this.running = false;
        this.ingestCounter.release(1000);
        //Wait for thread to join
        try {
            for (var worker : this.workers) {
                worker.join();
            }
        } catch (InterruptedException e) {throw new RuntimeException(e);}
    }
}
