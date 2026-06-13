package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * Elevation must only retarget <em>statically bound</em> calls. Rewriting {@code invoke g} to
 * {@code g$async} is sound only if both dispatch to the same receiver method; for an overridable
 * (virtual) callee they may not, since a subclass can override {@code g} without supplying a
 * matching {@code g$async}. So a caller that reaches suspension only through a virtual call is
 * left blocking, while one that reaches it through a {@code final} (statically bound) call is
 * elevated. See {@code AsyncTransformer.dispatchSafe}.
 */
public class DispatchSamples {

    final int seed;

    public DispatchSamples(int seed) {
        this.seed = seed;
    }

    /** Overridable awaiter — gets its own pair (direct await), but callers via it stay blocking. */
    public int vleaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + seed;
    }

    /** Final awaiter — statically bound, so callers through it may be elevated. */
    public final int fleaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + seed;
    }

    /** Calls the overridable {@link #vleaf}: must NOT be elevated (virtual dispatch). */
    public int callsVirtual(CompletableFuture<Integer> f) {
        return vleaf(f) * 2;
    }

    /** Calls the final {@link #fleaf}: safe to elevate. */
    public int callsFinal(CompletableFuture<Integer> f) {
        return fleaf(f) * 2;
    }
}
