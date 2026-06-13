package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * A three-level chain of virtually dispatched (interface default) methods, only the deepest of
 * which awaits. Exercises chain elevation (§7.7 / Profiler) together with the deepened runtime
 * transform (§7.9 / Elevation): when {@code leaf} blocks under load, the witness counts the whole
 * contiguous blocking chain ({@code leaf}, {@code mid}), so {@code mid}'s call site flips too; and
 * the state machine built for {@code mid} elevates <em>its</em> call to {@code leaf}, so suspension
 * propagates all the way down through per-receiver call sites resolved on demand.
 */
public interface ChainSamples {

    default int leaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    default int mid(CompletableFuture<Integer> f) {
        return leaf(f) * 2;     // INVOKEINTERFACE this.leaf — virtual
    }

    default int top(CompletableFuture<Integer> f) {
        return mid(f) + 100;    // INVOKEINTERFACE this.mid — virtual
    }

    final class Impl implements ChainSamples {}
}
