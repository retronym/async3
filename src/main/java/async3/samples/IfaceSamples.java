package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * The interface case. {@code indirect} calls {@code leaf} via {@code INVOKEINTERFACE this.leaf()} —
 * virtually dispatched. A direct {@code leaf$async} rewrite would be unsound: an implementer can
 * override {@code leaf} without a matching {@code leaf$async}. Strategy B (see
 * {@link async3.runtime.Elevation}) resolves the suspending entry against the <em>actual receiver</em>
 * at the call site, so {@link Impl} suspends through the interface's {@code leaf} while
 * {@link OverridingImpl} runs its own non-suspending override — each correct.
 */
public interface IfaceSamples {

    default int leaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    default int indirect(CompletableFuture<Integer> f) {
        return leaf(f) * 10;   // INVOKEINTERFACE this.leaf — elevated via the call site (Strategy B)
    }

    /** Inherits the interface {@code leaf}, which awaits — so it suspends when elevated. */
    final class Impl implements IfaceSamples {}

    /** Overrides {@code leaf} with a non-suspending body — must run the override, not the default. */
    final class OverridingImpl implements IfaceSamples {
        @Override public int leaf(CompletableFuture<Integer> f) {
            return 999;
        }
    }
}
