package async3.samples;

import async3.runtime.AsyncRT;
import java.util.concurrent.CompletableFuture;

/**
 * Methods that block only <em>indirectly</em> — they call a suspendable method without awaiting
 * themselves. Input for the §7.7 transitive-elevation transform: each must get a suspending
 * sibling whose suspension point is the call to the callee's {@code $async} entry, even though
 * its own source contains no {@code await}.
 *
 * <p>{@link #leaf}, {@link #greet} and {@link #addAll} are the only methods here that await
 * directly; everything else is suspendable purely by reaching one of them.
 */
public class ElevateSamples {

    /** The leaf: the only int-returning method that awaits directly. */
    public static int leaf(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    /** One level of indirection; blocks only because {@link #leaf} does. (primitive coercion) */
    public static int indirect(CompletableFuture<Integer> f) {
        return leaf(f) * 10;
    }

    /** Two levels: calls {@link #indirect}, which calls {@link #leaf}. */
    public static int twoLevel(CompletableFuture<Integer> f) {
        return indirect(f) + 7;
    }

    /** A direct await and an indirect one in the same method; {@code p} is live across the latter. */
    public static String mixed(CompletableFuture<Integer> a, CompletableFuture<Integer> b) {
        int p = AsyncRT.await(a);   // direct suspension
        int q = leaf(b);            // indirect suspension, with p live across it
        return p + ":" + q;
    }

    /** Object-typed callee: exercises the {@code CHECKCAST} coercion after the injected await. */
    public static String greet(CompletableFuture<String> f) {
        return "hi " + AsyncRT.await(f);
    }

    public static int greetLen(CompletableFuture<String> f) {
        return greet(f).length();   // indirect; String result is checkcast back, then consumed
    }

    /** Void-returning suspendable callee: exercises the {@code POP} coercion. */
    private static int total;

    public static void addAll(CompletableFuture<Integer> f) {
        total += AsyncRT.await(f);
    }

    public static int viaVoid(CompletableFuture<Integer> f, CompletableFuture<Integer> g) {
        total = 0;
        addAll(f);   // indirect, void
        addAll(g);   // indirect, void
        return total;
    }
}
