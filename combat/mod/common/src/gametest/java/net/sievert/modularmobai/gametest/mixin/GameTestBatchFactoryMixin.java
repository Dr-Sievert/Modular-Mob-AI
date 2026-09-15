package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.sievert.modularmobai.gametest.GameTestTuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(GameTestBatchFactory.class)
public class GameTestBatchFactoryMixin {

    @Inject(method = "fromTestFunction", at = @At("HEAD"), cancellable = true)
    private static void modular_mob_ai$resizeBatches(
            Collection<TestFunction> functions,
            ServerLevel level,
            CallbackInfoReturnable<Collection<GameTestBatch>> cir) {

        // The framework's own batching in a game-test server only. Nothing here can be reached outside one today, since a
        // development client is handed no suite and every size this reads then falls back to vanilla's, but the tests run by
        // hand with /test runall in a dev world go through this class too, and they are that world's to batch. See
        // GameTestTuning.gameTestServer.
        if (!GameTestTuning.gameTestServer()) {

            return;
        }

        final int batchSize = GameTestTuning.batchSize();

        if (batchSize <= 0) {

            return;
        }

        // Grouped by batch name first, exactly as vanilla does, so tests that asked to be kept apart still are.
        final Map<String, List<TestFunction>> byBatchName = new LinkedHashMap<>();

        for (TestFunction function : functions) {

            byBatchName.computeIfAbsent(function.batchName(), ignored -> new ArrayList<>()).add(function);
        }

        final List<GameTestBatch> batches = new ArrayList<>();

        for (Map.Entry<String, List<TestFunction>> group : byBatchName.entrySet()) {

            final List<TestFunction> inGroup = group.getValue();

            for (int start = 0, index = 0; start < inGroup.size(); start += batchSize, index++) {

                final List<GameTestInfo> infos = new ArrayList<>();

                for (TestFunction function : inGroup.subList(start, Math.min(start + batchSize, inGroup.size()))) {

                    infos.add(GameTestBatchFactory.toGameTestInfo(function, 0, level));
                }

                batches.add(GameTestBatchFactory.toGameTestBatch(infos, group.getKey() + ":" + index, level.getSeed()));
            }
        }

        cir.setReturnValue(batches);
    }

}
