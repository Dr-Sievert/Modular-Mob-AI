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
}
