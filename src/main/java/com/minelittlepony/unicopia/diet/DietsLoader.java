package com.minelittlepony.unicopia.diet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.minelittlepony.unicopia.Race;
import com.minelittlepony.unicopia.Unicopia;
import com.minelittlepony.unicopia.util.Resources;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

public class DietsLoader implements IdentifiableResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier ID = Unicopia.id("diets");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(Synchronizer sync, ResourceManager manager,
            Profiler prepareProfiler, Profiler applyProfiler,
            Executor prepareExecutor, Executor applyExecutor) {

        var dietsLoadTask = loadData(manager, prepareExecutor, "diets/races").thenApplyAsync(data -> {
            Map<Race, DietProfile> profiles = new HashMap<>();
            for (var entry : data.entrySet()) {
                Identifier id = entry.getKey();
                try {
                    Race.REGISTRY.getOrEmpty(id).ifPresentOrElse(race -> {
                        DietProfile.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                                .resultOrPartial(error -> LOGGER.error("Could not load diet profile {}: {}", id, error))
                                .ifPresent(profile -> profiles.put(race, profile));
                    }, () -> LOGGER.warn("Skipped diet for unknown race: " + id));
                } catch (Throwable t) {
                    LOGGER.error("Could not load diet profile {}", id, t);
                }
            }
            return profiles;
        }, prepareExecutor);

        var effectsLoadTask = loadData(manager, prepareExecutor, "diets/food_effects").thenApplyAsync(data -> data.entrySet().stream()
                    .map(entry -> {
                        try {
                        return Effect.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                                .resultOrPartial(error -> LOGGER.error("Could not load food effect {}: {}", entry.getKey(), error));
                        } catch (Throwable t) {
                            LOGGER.error("Could not load food effects {}", entry.getKey(), t);
                        }
                        return Optional.<Effect>empty();
                    })
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .toList(), prepareExecutor);

        return CompletableFuture.allOf(dietsLoadTask, effectsLoadTask).thenCompose(sync::whenPrepared).thenRunAsync(() -> {
            PonyDiets.load(new PonyDiets(
                    dietsLoadTask.getNow(Map.of()),
                    effectsLoadTask.getNow(List.of())
            ));
        }, applyExecutor);
    }

    private static CompletableFuture<Map<Identifier, JsonElement>> loadData(ResourceManager manager, Executor prepareExecutor, String path) {
        return CompletableFuture.supplyAsync(() -> {
            Map<Identifier, JsonElement> results = new HashMap<>();
            JsonDataLoader.load(manager, path, Resources.GSON, results);
            return results;
        });
    }
}
