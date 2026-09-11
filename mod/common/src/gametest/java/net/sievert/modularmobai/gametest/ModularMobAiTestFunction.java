package net.sievert.modularmobai.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ModularMobAiTestFunction {

    /**
     * Collects every game test declared by the given holder classes.
     *
     * @param classes The holder classes to scan.
     * @return The test functions declared by those holders.
     */
    public static Collection<TestFunction> getTestsFrom(Class<?>... classes) {

        return Stream.of(classes)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .flatMap(ModularMobAiTestFunction::of)
                .collect(Collectors.toList());
    }

    /**
     * Turns a single method into the test functions it declares. A plain test yields one, a test carrying
     * {@link RepeatGameTest} yields one per run, each named after its index so the framework keeps them apart.
     *
     * @param method The method to convert.
     * @return The test functions, or an empty stream when the method is not a game test.
     */
    public static Stream<TestFunction> of(Method method) {

        final GameTest gameTest = method.getAnnotation(GameTest.class);

        if (gameTest == null) {

            return Stream.empty();
        }

        if (!Modifier.isStatic(method.getModifiers())) {

            throw new IllegalArgumentException("Test must be static: " + method.getName());
        }

        if (!hasTestSignature(method)) {

            throw new IllegalArgumentException(
                    "Test must take a GameTestHelper, optionally followed by an int run index: " + method.getName());
        }

        final Class<?> owner = method.getDeclaringClass();
        final GameTestGroup group = owner.getAnnotation(GameTestGroup.class);

        if (group == null) {

            throw new IllegalArgumentException(owner.getName() + " must be annotated with @GameTestGroup");
        }

        final String base = "%s:gametest".formatted(group.namespace());

        final String structure = group.path().isEmpty()
                ? "%s/%s".formatted(base, gameTest.template())
                : "%s/%s/%s".formatted(base, group.path(), gameTest.template());

        final String name = owner.getSimpleName() + "." + method.getName();
        final RepeatGameTest repeat = method.getAnnotation(RepeatGameTest.class);

        if (repeat == null) {

            return Stream.of(build(method, gameTest, structure, name, 1));
        }

        if (repeat.value() < 1) {

            throw new IllegalArgumentException("Repeat count must be at least one: " + method.getName());
        }

        // The annotation is the default; a run can ask for a different number without the code being recompiled.
        final int runs = GameTestTuning.arenaCount(repeat.value());

        // Padded so the runs sort in order rather than 1, 10, 100, 11.
        final String format = "%s_%0" + String.valueOf(runs).length() + "d";

        // Only this worker's slice is generated. Every worker still names its arenas by their index in the whole suite, so
        // an arena keeps the same name no matter how the run was split up.
        final int shardCount = GameTestTuning.shardCount();
        final int shardIndex = GameTestTuning.shardIndex();

        return IntStream.rangeClosed(1, runs)
                .filter(run -> shardCount <= 1 || (run - 1) % shardCount == shardIndex)
                .mapToObj(run -> build(method, gameTest, structure, format.formatted(name, run), run));
    }

    private static boolean hasTestSignature(Method method) {

        final Class<?>[] parameters = method.getParameterTypes();

        if (parameters.length < 1 || !GameTestHelper.class.isAssignableFrom(parameters[0])) {

            return false;
        }

        return parameters.length == 1 || (parameters.length == 2 && parameters[1] == int.class);
    }

    private static TestFunction build(Method method, GameTest gameTest, String structure, String name, int run) {

        final boolean takesRun = method.getParameterCount() == 2;

        return new TestFunction(
                gameTest.batch(),
                name,
                structure,
                Rotation.NONE,
                gameTest.timeoutTicks(),
                gameTest.setupTicks(),
                gameTest.required(),
                helper -> {

                    try {

                        if (takesRun) {

                            method.invoke(null, helper, run);
                        }

                        else {

                            method.invoke(null, helper);
                        }
                    }

                    // Unwrapped so that an assertion a test throws reaches the framework as the failure it is, rather
                    // than as a reflection error wrapped around one.
                    catch (InvocationTargetException exception) {

                        if (exception.getCause() instanceof RuntimeException runtime) {

                            throw runtime;
                        }

                        throw new RuntimeException(exception.getCause());
                    }

                    catch (IllegalAccessException exception) {

                        throw new RuntimeException(exception);
                    }
                }
        );
    }
}
