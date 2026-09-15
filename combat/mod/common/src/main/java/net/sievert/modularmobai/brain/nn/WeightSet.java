package net.sievert.modularmobai.brain.nn;

/**
 * One trained network: its shape, its parameters, and the schema it was trained against.
 *
 * <p>Immutable and shared by reference. Every agent driven by the same weights reads the same array, and nothing at
 * runtime ever writes to it; the only state an agent owns is its own hidden vector.
 *
 * @param id        where the weights came from, for logs and for telling buckets apart
 * @param schemaId  the observation and action layout the network was trained against
 * @param iteration which training iteration produced these, for logs
 * @param obsClip   how far the normalised observation is clamped either side of zero
 * @param params    {@link Topology#size()} floats, in the topology's segment order
 */
public record WeightSet(String id, int schemaId, int iteration, Topology topology, float obsClip, float[] params) {

    public WeightSet {

        if (params.length != topology.size()) {

            throw new IllegalArgumentException(id + " holds " + params.length + " parameters but its topology needs "
                    + topology.size());
        }
    }
}
