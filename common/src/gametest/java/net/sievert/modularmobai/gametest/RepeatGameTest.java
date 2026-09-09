package net.sievert.modularmobai.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatGameTest {

    /**
     * How many copies of the test to run.
     *
     * @return The number of runs, at least one.
     */
    int value();
}
