package net.sievert.modularmobai.gametest.tools;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;

/**
 * Writes every mob's shape out of the game, as JSON the replay viewer draws its fighters from.
 *
 * <pre>
 *   models &lt;dir&gt;    one &lt;entity&gt;.json a mob, plus index.json naming them all
 * </pre>
 *
 * <p>Why this exists: the viewer's 3D view drew every fighter as the same humanoid box with that mob's skin wrapped round
 * it, so a polar bear, a spider and a ghast all came out person-shaped. Vanilla's entity models are not hard to draw —
 * each is a tree of cuboids with a pivot, a rotation and a texture offset, and nothing else — but they live in the
 * client's Java classes, which a browser cannot read. The alternative to porting them by hand, one mob at a time, is to
 * ask the game: {@link LayerDefinitions#createRoots()} builds every model the client registers as plain data, no window
 * and no rendering, so this tool walks those trees and writes the numbers down.
 *
 * <p>What comes out is generated data derived from the game, not a Mojang asset: cuboid corners, sizes and texture
 * offsets, which is the same kind of thing as a block's shape. No texture and no image is written or read here. Mob
 * skins still come from the user's own Minecraft jar while the viewer serves, as they always did.
 *
 * <p>Only the base layer of each mob is taken. The extra layers a mob has — a wolf's collar and armour, a piglin's outer
 * skin, a slime's shell, a breeze's wind, the armour layers every humanoid carries — are named in each file under
 * {@code otherLayers} and drawn by nothing; a fighter in the viewer wears no armour, so there is nothing for them to do.
 *
 * <p>Each file also carries the entity's own width and height, which is the viewer's cross-check that a shape is being
 * drawn at the size the game gives it: nothing here says what the client's renderer scales a model by (that is code, not
 * data), so the viewer keeps those few scales itself and this is what catches one of them being wrong.
 */
public final class MobModelTool {

    private MobModelTool() {}

    /**
     * Which model layer each mob is drawn with, and which mobs are worth writing at all: every opponent the league
     * fields (see gametest/league/Roster), which is also every mob a replay can hold, plus the player shape the agent
     * itself is drawn in.
     *
     * <p>Every one of these is {@code ModelLayers.<the id in capitals>}, so the layer is looked up by name rather than
     * listed twice; the agent is the exception, being no vanilla entity at all.
     */
    private static final List<String> MOBS = List.of(
            "zombie", "husk", "drowned", "zombie_villager",
            "skeleton", "stray", "bogged", "wither_skeleton",
            "spider", "cave_spider", "creeper",
            "vindicator", "pillager", "evoker", "witch", "ravager",
            "enderman", "silverfish", "endermite",
            "slime", "magma_cube",
            "zombified_piglin", "piglin", "piglin_brute", "hoglin", "zoglin",
            "breeze", "blaze", "ghast", "phantom", "vex", "bee",
            "wolf", "polar_bear", "iron_golem", "snow_golem", "warden");

    /** The agent is a player-shaped mob of the mod's own, so it takes the player's wide-armed layer. */
    private static final String AGENT = "player";

    public static void main(String[] arguments) throws IOException {

        if (arguments.length != 2 || !arguments[0].equals("models")) {

            System.err.println("usage: MobModelTool models <dir>");
            System.exit(2);
            return;
        }

        // The model classes themselves touch nothing but geometry, but createRoots() builds every layer the client has,
        // and a few of those reach for a block or a wood type on the way. Those want the registries up, which is what
        // this is; it costs a couple of seconds and saves guessing which layers are safe to ask for.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Path folder = Path.of(arguments[1]);
        Files.createDirectories(folder);

        Map<ModelLayerLocation, LayerDefinition> roots = LayerDefinitions.createRoots();
        Map<String, ModelLayerLocation> named = layersByName();

        List<String> written = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        // The two right-hand columns are the check: a shape drawn at the size the game gives the mob has them close, and
        // one that does not wants a scale in the viewer's table (which is where the client's renderer keeps its own).
        System.out.printf(Locale.ROOT, "%-20s %-26s %9s %8s %8s %8s %8s%n",
                "mob", "layer", "texture", "model w", "mob w", "model h", "mob h");

        for (String id : allShapes()) {

            String field = id.toUpperCase(Locale.ROOT);
            ModelLayerLocation layer = named.get(field);
            LayerDefinition definition = layer == null ? null : roots.get(layer);

            if (definition == null) {

                missing.add(id + " (ModelLayers." + field + (layer == null ? " is not a field" : " has no definition") + ")");
                continue;
            }

            // LayerDefinition hands out nothing but a bake, which would want a graphics context, so its mesh and its
            // texture size are read straight off it.
            MeshDefinition mesh = (MeshDefinition) read(definition, "mesh");
            int[] texture = textureSize(definition);
            PartDefinition root = mesh.getRoot();

            Bounds bounds = new Bounds();
            StringBuilder parts = new StringBuilder();
            children(root, parts, bounds, "", new org.joml.Matrix4f());

            EntityDimensions size = id.equals(AGENT) ? null : dimensionsOf(id);

            StringBuilder out = new StringBuilder();
            out.append("{\n");
            out.append("  \"mob\": \"").append(id).append("\",\n");
            out.append("  \"layer\": \"").append(layer.getModel()).append('#').append(layer.getLayer()).append("\",\n");
            out.append("  \"texture\": [").append(texture[0]).append(", ").append(texture[1]).append("],\n");

            if (size != null) {

                out.append("  \"entity\": [").append(number(size.width())).append(", ")
                        .append(number(size.height())).append("],\n");
            }

            out.append("  \"bounds\": [").append(bounds.toJson()).append("],\n");

            List<String> others = otherLayers(layer, roots.keySet());

            if (!others.isEmpty()) {

                out.append("  \"otherLayers\": [");

                for (int i = 0; i < others.size(); i++) {

                    out.append(i == 0 ? "" : ", ").append('"').append(others.get(i)).append('"');
                }

                out.append("],\n");
            }

            out.append("  \"parts\": {").append(parts).append("\n  }\n}\n");

            Files.writeString(folder.resolve(id + ".json"), out.toString(), StandardCharsets.UTF_8);
            written.add(id);

            System.out.printf(Locale.ROOT, "%-20s %-26s %4dx%-4d %8s %8s %8s %8s%n", id,
                    layer.getModel().getPath() + "#" + layer.getLayer(), texture[0], texture[1],
                    number(bounds.width() / 16.0F), size == null ? "-" : number(size.width()),
                    number(bounds.height() / 16.0F), size == null ? "-" : number(size.height()));
        }

        StringBuilder index = new StringBuilder();
        index.append("{\n");
        index.append("  \"note\": \"Cuboid shapes read out of Minecraft's own model classes by ")
                .append("gradlew :fabric:exportMobModels (gametest/tools/MobModelTool). Generated data, not a Mojang ")
                .append("asset: no texture or image is here. Regenerate rather than edit.\",\n");
        index.append("  \"minecraft\": \"").append(SharedConstants.getCurrentVersion().getName()).append("\",\n");
        index.append("  \"mobs\": [");

        for (int i = 0; i < written.size(); i++) {

            index.append(i == 0 ? "" : ", ").append('"').append(written.get(i)).append('"');
        }

        index.append("]\n}\n");
        Files.writeString(folder.resolve("index.json"), index.toString(), StandardCharsets.UTF_8);

        System.out.println("Wrote " + written.size() + " mob shapes to " + folder.toAbsolutePath());

        if (!missing.isEmpty()) {

            // A mob whose layer cannot be found is a real gap -- the viewer falls back to a humanoid box for it -- so it
            // fails the task rather than being mentioned in passing and forgotten.
            throw new IOException("No model layer for: " + String.join(", ", missing));
        }
    }

    /** Every shape to write: the mobs a replay can hold, and the player shape the agent is drawn in. */
    private static List<String> allShapes() {

        List<String> all = new ArrayList<>(MOBS);
        all.add(AGENT);
        all.sort(Comparator.naturalOrder());
        return all;
    }

    /** {@code ModelLayers}' own fields, so a mob's layer is found by its id in capitals instead of being listed twice. */
    private static Map<String, ModelLayerLocation> layersByName() {

        Map<String, ModelLayerLocation> named = new TreeMap<>();

        for (Field field : ModelLayers.class.getDeclaredFields()) {

            if (field.getType() == ModelLayerLocation.class) {

                try {

                    field.setAccessible(true);
                    named.put(field.getName(), (ModelLayerLocation) field.get(null));
                }

                catch (ReflectiveOperationException ignored) {

                    // A field that cannot be read is the same as one that is not there: the mob is reported missing.
                }
            }
        }

        return named;
    }

    /**
     * Every other layer registered for the same model, which is what a mob's armour, collar, outer skin or wind swirl
     * is. Written down so a file says what it leaves out rather than quietly being half a mob.
     */
    private static List<String> otherLayers(ModelLayerLocation base, Set<ModelLayerLocation> all) {

        List<String> others = new ArrayList<>();

        for (ModelLayerLocation layer : all) {

            if (layer.getModel().equals(base.getModel()) && !layer.getLayer().equals(base.getLayer())) {

                others.add(layer.getLayer());
            }
        }

        others.sort(Comparator.naturalOrder());
        return others;
    }

    /** The entity's own width and height, the viewer's cross-check that a shape is drawn at the size the game gives it. */
    private static EntityDimensions dimensionsOf(String id) {

        // byString, not the registry, which is defaulted: an id it does not know comes back as a pig rather than as
        // nothing at all.
        return EntityType.byString(id).map(EntityType::getDimensions).orElse(null);
    }

    private static int[] textureSize(LayerDefinition definition) throws IOException {

        // MaterialDefinition is package private in all but name, so its two numbers are read rather than asked for.
        // Baking the layer to find them out instead would need a graphics context.
        Object material = read(definition, "material");
        return new int[] {(int) (Integer) read(material, "xTexSize"), (int) (Integer) read(material, "yTexSize")};
    }

    /**
     * A part's children as JSON, and the box the whole tree covers. The pivots, rotations and cube numbers come out
     * exactly as the game holds them, in Minecraft's model space: sixteen units to the block, y pointing down, the face
     * towards -z. The viewer turns that space into its own once, at the root, so every number in a file is the game's.
     */
    private static void children(PartDefinition part, StringBuilder out, Bounds bounds, String indent,
                                 org.joml.Matrix4f above) throws IOException {

        @SuppressWarnings("unchecked")
        Map<String, PartDefinition> kids = (Map<String, PartDefinition>) read(part, "children");

        // By name. The game keeps a part's children in a plain hash map, whose order says nothing, and a file that is
        // regenerated wants to come out byte for byte the same when the game has not changed.
        int written = 0;

        for (Map.Entry<String, PartDefinition> entry : new TreeMap<>(kids).entrySet()) {

            out.append(written++ == 0 ? "\n" : ",\n").append(indent).append("    \"").append(entry.getKey()).append("\": ");
            part(entry.getValue(), out, bounds, indent + "    ", above);
        }
    }

    private static void part(PartDefinition part, StringBuilder out, Bounds bounds, String indent,
                             org.joml.Matrix4f above) throws IOException {

        PartPose pose = (PartPose) read(part, "partPose");

        @SuppressWarnings("unchecked")
        List<CubeDefinition> cubes = (List<CubeDefinition>) read(part, "cubes");

        // Where this part sits at rest, pivots and resting rotations of everything above it included: it is what the
        // whole model's size is measured in, the one check that a shape is drawn as big as the game's own mob.
        // ModelPart.translateAndRotate turns about z, then y, then x, which is the order this is.
        org.joml.Matrix4f here = new org.joml.Matrix4f(above).translate(pose.x, pose.y, pose.z)
                .rotateZ(pose.zRot).rotateY(pose.yRot).rotateX(pose.xRot);

        out.append('{');
        String separator = "";

        if (pose.x != 0.0F || pose.y != 0.0F || pose.z != 0.0F) {

            out.append("\"at\": [").append(number(pose.x)).append(", ").append(number(pose.y)).append(", ")
                    .append(number(pose.z)).append(']');
            separator = ", ";
        }

        if (pose.xRot != 0.0F || pose.yRot != 0.0F || pose.zRot != 0.0F) {

            out.append(separator).append("\"rot\": [").append(number(pose.xRot)).append(", ").append(number(pose.yRot))
                    .append(", ").append(number(pose.zRot)).append(']');
            separator = ", ";
        }

        if (!cubes.isEmpty()) {

            out.append(separator).append("\"boxes\": [");

            for (int i = 0; i < cubes.size(); i++) {

                out.append(i == 0 ? "" : ", ");
                box(cubes.get(i), out, bounds, here);
            }

            out.append(']');
            separator = ", ";
        }

        @SuppressWarnings("unchecked")
        Map<String, PartDefinition> kids = (Map<String, PartDefinition>) read(part, "children");

        if (!kids.isEmpty()) {

            out.append(separator).append("\"kids\": {");
            children(part, out, bounds, indent, here);
            out.append('\n').append(indent).append("  }");
        }

        out.append('}');
    }

    /**
     * One cube, in the order CubeListBuilder takes them and the viewer's own box code already reads:
     * {@code [u, v, x, y, z, width, height, depth, grow, mirror]}, with two more only where a cube needs them — a
     * texture scale other than one to one, and the faces to draw when a cube has fewer than six.
     */
    private static void box(CubeDefinition cube, StringBuilder out, Bounds bounds, org.joml.Matrix4f here)
            throws IOException {

        org.joml.Vector3f origin = (org.joml.Vector3f) read(cube, "origin");
        org.joml.Vector3f dimensions = (org.joml.Vector3f) read(cube, "dimensions");
        Object grow = read(cube, "grow");
        Object texCoord = read(cube, "texCoord");
        Object texScale = read(cube, "texScale");
        boolean mirror = (Boolean) read(cube, "mirror");

        @SuppressWarnings("unchecked")
        Set<Direction> faces = (Set<Direction>) read(cube, "visibleFaces");

        float growX = (Float) read(grow, "growX");
        float growY = (Float) read(grow, "growY");
        float growZ = (Float) read(grow, "growZ");
        float scaleU = (Float) read(texScale, "u");
        float scaleV = (Float) read(texScale, "v");

        out.append('[').append(number((Float) read(texCoord, "u"))).append(", ").append(number((Float) read(texCoord, "v")));
        out.append(", ").append(number(origin.x())).append(", ").append(number(origin.y())).append(", ")
                .append(number(origin.z()));
        out.append(", ").append(number(dimensions.x())).append(", ").append(number(dimensions.y())).append(", ")
                .append(number(dimensions.z()));

        if (growX == growY && growY == growZ) {

            out.append(", ").append(number(growX));
        }

        else {

            out.append(", [").append(number(growX)).append(", ").append(number(growY)).append(", ")
                    .append(number(growZ)).append(']');
        }

        out.append(", ").append(mirror);

        boolean partial = faces.size() < Direction.values().length;

        if (scaleU != 1.0F || scaleV != 1.0F || partial) {

            out.append(", [").append(number(scaleU)).append(", ").append(number(scaleV)).append(']');
        }

        if (partial) {

            out.append(", [");

            int written = 0;

            for (Direction direction : Direction.values()) {

                if (faces.contains(direction)) {

                    out.append(written++ == 0 ? "" : ", ").append('"')
                            .append(direction.getName().toLowerCase(Locale.ROOT)).append('"');
                }
            }

            out.append(']');
        }

        out.append(']');

        // All eight corners through the resting pose, since a part that rests turned -- a polar bear's body lies on its
        // side in the model -- is a different size the other way round.
        for (int corner = 0; corner < 8; corner++) {

            float cx = origin.x() + ((corner & 1) == 0 ? -growX : dimensions.x() + growX);
            float cy = origin.y() + ((corner & 2) == 0 ? -growY : dimensions.y() + growY);
            float cz = origin.z() + ((corner & 4) == 0 ? -growZ : dimensions.z() + growZ);
            org.joml.Vector3f at = here.transformPosition(new org.joml.Vector3f(cx, cy, cz));
            bounds.add(at.x(), at.y(), at.z());
        }
    }

    /** A private field, by name. The builder classes keep their numbers to themselves and hand out nothing but a bake. */
    private static Object read(Object owner, String name) throws IOException {

        Class<?> type = owner.getClass();

        while (type != null) {

            try {

                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            }

            catch (NoSuchFieldException missing) {

                type = type.getSuperclass();
            }

            catch (ReflectiveOperationException | RuntimeException failed) {

                throw new IOException("Could not read " + owner.getClass().getName() + "." + name, failed);
            }
        }

        throw new IOException("No field " + name + " on " + owner.getClass().getName());
    }

    /** Shortest form that reads back the same, so a file is numbers rather than a wall of zeroes. */
    private static String number(float value) {

        if (value == Math.rint(value) && Math.abs(value) < 1.0E7F) {

            return String.valueOf((long) value);
        }

        String text = String.format(Locale.ROOT, "%.5f", value);

        while (text.endsWith("0")) {

            text = text.substring(0, text.length() - 1);
        }

        // A value too small to show at five places rounds to nothing, and "0." is not a number in JSON.
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** The box a whole model covers, in model units, for the size check. */
    private static final class Bounds {

        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        void add(float x, float y, float z) {

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        float height() {

            return maxY > minY ? maxY - minY : 0.0F;
        }

        /** The wider of the two horizontal spans, which is what an entity's own width is. */
        float width() {

            return maxX > minX ? Math.max(maxX - minX, maxZ - minZ) : 0.0F;
        }

        String toJson() {

            if (maxY <= minY) {

                return "0, 0, 0, 0, 0, 0";
            }

            return number(minX) + ", " + number(minY) + ", " + number(minZ) + ", "
                    + number(maxX) + ", " + number(maxY) + ", " + number(maxZ);
        }
    }
}
