package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * The interface case. A default method calling another default method compiles to
 * {@code INVOKEINTERFACE this.g()} — virtually dispatched — so elevating it would be unsound: an
 * implementer can override {@code leaf} without a matching {@code leaf$async}, and the rewritten
 * {@code indirect$async} would then run the interface's body instead of the override. Elevation
 * must therefore <em>not</em> produce {@code indirect$async}; {@code leaf} still gets its own pair
 * as a direct awaiter (sound to invoke on a non-overriding receiver).
 */
public interface IfaceSamples {

    default int leaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    default int indirect(CompletableFuture<Integer> f) {
        return leaf(f) * 10;   // INVOKEINTERFACE this.leaf — virtual, must not be elevated
    }

    /** A concrete implementer that does not override {@code leaf}, so it inherits both siblings. */
    final class Impl implements IfaceSamples {}
}
