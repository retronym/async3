package async3.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The witness behind dynamic ("tier-flip") elevation (docs/DESIGN.md §7.7). The blocking
 * {@link AsyncRT#await} is its own profiler: when a future is not yet complete — a real carrier
 * block is imminent — it calls {@link #observeBlock}, which takes a {@link StackWalker} sample of
 * the live stack, attributes the block to the nearest user frame (the method that called
 * {@code await}), and counts it. When a method's blocking count crosses {@link #threshold()}, it
 * is marked <em>hot</em>; the Strategy-B call site ({@link Elevation}) consults {@link #isHot} and
 * flips that method from the blocking tier to a suspending state machine on its next call.
 *
 * <p>This is the runtime analogue of the static suspendability closure: instead of elevating
 * everything reachable from an {@code await} ahead of time, it elevates only the methods observed
 * to actually block, on the hot path — and against the actual dispatched method, so it composes
 * with the per-receiver resolution {@link Elevation} already does. Counting happens only on the
 * slow (blocking) path, where a stack walk is cheap next to the block it precedes.
 */
public final class Profiler {
    private Profiler() {}

    private static volatile int threshold = Integer.getInteger("async3.elevate.threshold", 8);
    private static final ConcurrentHashMap<String, AtomicInteger> BLOCKS = new ConcurrentHashMap<>();
    private static final Set<String> HOT = ConcurrentHashMap.newKeySet();
    private static final AtomicLong blockingAwaits = new AtomicLong();
    private static final AtomicLong elevations = new AtomicLong();

    // RETAIN_CLASS_REFERENCE is required for StackFrame.getDescriptor().
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    // Frames to skip while walking down to the awaiting user method.
    private static final String RT = "async3.runtime.AsyncRT";
    private static final String ASYNC = "async3.runtime.Async";
    private static final String SELF = "async3.runtime.Profiler";

    /**
     * Record an imminent block. Called from {@link AsyncRT#await} when the future is not done.
     * The witness is the nearest stack frame outside the marker/runtime classes — the method
     * that is about to block on this {@code await} — keyed as {@code class.name+descriptor}.
     */
    public static void observeBlock() {
        blockingAwaits.incrementAndGet();
        String key = WALKER.walk(frames -> frames
                .map(f -> f.getClassName() + "." + f.getMethodName() + f.getDescriptor())
                .filter(k -> !k.startsWith(RT) && !k.startsWith(ASYNC) && !k.startsWith(SELF))
                .findFirst().orElse(null));
        if (key == null) return;
        if (BLOCKS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() >= threshold)
            HOT.add(key);
    }

    /** Whether {@code methodKey} ({@code class.name+descriptor}) has crossed the hot threshold. */
    public static boolean isHot(String methodKey) {
        return HOT.contains(methodKey);
    }

    /** {@link Elevation} reports a tier flip here, for visibility. */
    static void recordElevation() {
        elevations.incrementAndGet();
    }

    // ---- introspection / control (tests, demos)

    public static int blockCount(String methodKey) {
        AtomicInteger a = BLOCKS.get(methodKey);
        return a == null ? 0 : a.get();
    }

    public static long blockingAwaits() { return blockingAwaits.get(); }

    public static long elevations() { return elevations.get(); }

    public static int threshold() { return threshold; }

    public static void setThreshold(int t) { threshold = t; }

    /** Clear all counters and the hot set (test isolation; a fresh process starts empty anyway). */
    public static void reset() {
        BLOCKS.clear();
        HOT.clear();
        blockingAwaits.set(0);
        elevations.set(0);
    }
}
