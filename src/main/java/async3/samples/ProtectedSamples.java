package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * A non-final class whose awaiting method is {@code protected} (so the elevated call is a virtual
 * {@code INVOKEVIRTUAL} routed through Strategy B). Exercises the call site's target resolution
 * hardening: {@code Class.getMethod} would miss {@code leaf}, so {@code Elevation} falls back to
 * walking the hierarchy with {@code getDeclaredMethod} and making it accessible.
 */
public class ProtectedSamples {

    protected int leaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    public int caller(CompletableFuture<Integer> f) {
        return leaf(f) * 3;     // INVOKEVIRTUAL this.leaf — protected, non-final → virtual → Strategy B
    }
}
