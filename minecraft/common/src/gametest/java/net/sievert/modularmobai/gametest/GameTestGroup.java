package net.sievert.modularmobai.gametest;

import net.sievert.modularmobai.Constants;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameTestGroup {

    /**
     * An optional folder below "gametest" that holds the structures for this group.
     *
     * @return The structure sub folder, or an empty string to read straight from "gametest".
     */
    String path() default "";

    /**
     * The namespace the structures of this group are loaded from.
     *
     * @return The namespace of the structures.
     */
    String namespace() default Constants.MOD_ID;
}
