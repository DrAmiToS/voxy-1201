package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.AbstractFarWorldRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.fabricmc.loader.api.FabricLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport> {
    public static final boolean VIVECRAFT_INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();
    private static final Object NO_VIVECRAFT_PASS = new Object();

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getVivecraftViewport() {
        var pass = getVivecraftPass();
        if (pass == NO_VIVECRAFT_PASS) {
            return this.defaultViewport;
        }
        return this.extraViewports.computeIfAbsent(pass, a->this.creator.get());
    }

    private static Object getVivecraftPass() {
        try {
            var holderClass = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            var holder = holderClass.getMethod("getInstance").invoke(null);
            var pass = holderClass.getField("currentPass").get(holder);
            return pass == null ? NO_VIVECRAFT_PASS : pass;
        } catch (ReflectiveOperationException e) {
            return NO_VIVECRAFT_PASS;
        }
    }

    public T getViewport() {
        if (VIVECRAFT_INSTALLED) {
            return getVivecraftViewport();
        }
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
