package net.sievert.modularmobai.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatGameTest {

    /**
     * How many copies of the test to run, when nothing else says otherwise.
     *
     * <p>Leave this alone. The arenas property in gradle.properties is what decides, because the build has to know the
     * number before any of this is compiled in order to size a parallel run, and a count living in the test would leave
     * the runner sizing every run from the one before it. This is only what happens if that property goes missing.
     *
     * @return The number of runs, at least one.
     */
    int value() default 1;

    /**
     * Whether each generated test is a slot rather than a run: only as many tests as run at once, each working through
     * runs from a queue the process shares until it is empty. The number of runs stays the same; what changes is that a
     * slot whose run has finished starts the next one straight away, instead of waiting for the rest of its batch.
     *
     * @return Whether the test takes its runs from a shared queue. The int it is handed is then its slot, not a run.
     */
    boolean slots() default false;
}
