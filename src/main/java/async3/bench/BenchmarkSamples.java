package async3.bench;

import async3.runtime.AsyncRT;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;

/**
 * Source-shape samples for the frame-store benchmarks. Every method calls the {@code AsyncRT.await}
 * marker; the transformer converts each into a state machine under the chosen store
 * ({@code array-spill} / {@code array-live} / {@code typed-fields}).
 *
 * <p>The four shapes cover distinct axes from docs/DESIGN.md §9:
 * <ol>
 *   <li>{@link #single}: baseline — one await, no extra locals. Measures raw state-machine overhead.</li>
 *   <li>{@link #wide}: five int locals cross the single suspension point, maximising the spill count.
 *       {@code array-spill} pays O(5) spill/restore even on the fast path; live stores pay nothing.</li>
 *   <li>{@link #chain3}: three sequential awaits, each carrying the accumulated result of the
 *       previous. Multiplies the per-suspension costs by 3.</li>
 *   <li>{@link #hotInner}: tight 100-iteration inner loop with no await, then one suspension.
 *       {@code array-live} and {@code typed-fields} pay a per-access overhead (Frames helper /
 *       {@code getfield}) on every loop iteration; {@code array-spill} uses free JVM slots inside
 *       the loop and spills only once at the suspension. This is Kotlin's motivation for caching
 *       typed fields in JVM locals inside hot regions (docs/DESIGN.md §9 final refinement).</li>
 *   <li>{@link #loopAwait8}: eight sequential awaits inside a loop (the {@code loopSum} pattern).
 *       One suspension per iteration; tests accumulation of per-suspension cost across a loop.</li>
 * </ol>
 */
public class BenchmarkSamples {

    /** 1. Minimal: one await, no extra locals crossing it. */
    public static int single(CompletableFuture<Integer> f) {
        return AsyncRT.await(f) + 1;
    }

    /**
     * 2. Wide live-set: five int parameters all live across the single suspension point.
     * {@code array-spill} spills five slots at each suspension (and restores five on resume);
     * live stores ({@code array-live}, {@code typed-fields}) have zero local spill cost.
     */
    public static int wide(CompletableFuture<Integer> trigger, int a, int b, int c, int d, int e) {
        int sum = a + b + c + d + e;
        sum += AsyncRT.await(trigger);
        return sum;
    }

    /**
     * 3. Chain: three sequential awaits, each accumulating the previous result.
     * The per-suspension overhead of each store compounds across all three suspension points.
     */
    public static int chain3(CompletableFuture<Integer> f1,
                              CompletableFuture<Integer> f2,
                              CompletableFuture<Integer> f3) {
        int a = AsyncRT.await(f1);
        int b = AsyncRT.await(f2) + a;
        return AsyncRT.await(f3) + b;
    }

    /**
     * 4. Hot inner loop: 100-iteration loop body with no await, then one suspension.
     *
     * <p>In {@code array-live} mode each iteration touches {@code sum} and {@code i} via
     * {@link async3.runtime.Frames} helpers (an {@code INVOKESTATIC} + array read/write per
     * access); in {@code typed-fields} mode each access is a {@code getfield}/{@code putfield};
     * in {@code array-spill} mode both variables live in free JVM slots for the loop's duration
     * and are spilled only once at the single suspension.
     *
     * <p>This is the shape that motivates Kotlin's register-allocation refinement: cache typed
     * fields in JVM locals inside hot regions and write them back only around suspensions.
     */
    public static int hotInner(CompletableFuture<Integer> f) {
        int sum = 0;
        for (int i = 0; i < 100; i++) sum += i;   // hot loop: sum and i accessed ~300× with no await
        return sum + AsyncRT.await(f);
    }

    /**
     * 5. Loop-with-await: one suspension per iteration over 8 elements.
     * {@code sum} and {@code i} are live across every suspension point, so typed-fields
     * promotes them to named fields and must write them back at each of the 8 suspensions.
     */
    public static int loopAwait8(CompletableFuture<Integer>[] fs) {
        int sum = 0;
        for (int i = 0; i < 8; i++) sum += AsyncRT.await(fs[i]);
        return sum;
    }

    /**
     * 6. Await-then-loop: one suspension to obtain a loop bound, then a loop up to that bound.
     *
     * <p>{@code hi} is live across the suspension so it lands in a SM field for
     * {@code array-live} and {@code typed-fields}; {@code array-spill} restores it into a free
     * JVM local slot before the loop. The JIT can trivially register-allocate a local; a field
     * load in the loop condition requires alias analysis to hoist. This is the shape from the
     * discussion: the loop bound is not a compile-time constant but a value produced by an await.
     */
    public static int awaitThenLoop(CompletableFuture<Integer> fhi, int[] arr) {
        int hi = AsyncRT.await(fhi);
        int x = 0;
        for (int i = 0; i < hi && i < arr.length; i++) x += arr[i];
        return x;
    }

    /**
     * Returns a full-access lookup whose lookup class is {@link BenchmarkSamples}, so
     * {@link MethodHandles#privateLookupIn} and {@code defineHiddenClass(NESTMATE)} work from the
     * benchmark setup code, which lives in a different class.
     */
    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }
}
