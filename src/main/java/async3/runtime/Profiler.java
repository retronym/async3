package async3.runtime;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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

    /**
     * Record an imminent block. Called from {@link AsyncRT#await} when the future is not done.
     * The witness is the <em>contiguous run of blocking user frames</em> above this await, up to
     * the elevation boundary (the first runtime/reflection/generated frame below them) — the
     * direct awaiter and every blocking caller it reaches through. Each is counted, so a deeper
     * caller becomes hot and flips its own call site too (chain elevation), not just the leaf.
     * Counting only this contiguous run keeps it to the methods actually worth elevating; frames
     * already on the suspending tier (their bodies use {@code getCompleted}/{@code onComplete},
     * not {@code await}) never reach here.
     */
    public static void observeBlock() {
        blockingAwaits.incrementAndGet();
        List<String> chain = WALKER.walk(frames -> frames
                .dropWhile(f -> !isUser(f))   // skip Profiler / AsyncRT.await / Async.await
                .takeWhile(Profiler::isUser)  // the blocking chain, stopping at the boundary
                .map(Profiler::keyOf)
                .collect(Collectors.toList()));
        for (String key : chain)
            if (BLOCKS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() >= threshold)
                HOT.add(key);
    }

    private static String keyOf(StackWalker.StackFrame f) {
        return f.getClassName() + "." + f.getMethodName() + f.getDescriptor();
    }

    /** A blocking-tier application frame — not runtime/marker/reflection, not a generated entry/body. */
    private static boolean isUser(StackWalker.StackFrame f) {
        String cn = f.getClassName();
        if (cn.startsWith("async3.runtime") || cn.startsWith("async3.transform")
                || cn.startsWith("java.") || cn.startsWith("jdk.")
                || cn.startsWith("sun.") || cn.startsWith("com.sun."))
            return false;
        return !f.getMethodName().contains("$async");   // $async entry / $asyncBody is already elevated
    }

    /** Whether {@code methodKey} ({@code class.name+descriptor}) has crossed the hot threshold. */
    public static boolean isHot(String methodKey) {
        return HOT.contains(methodKey);
    }

    /**
     * Profile-driven frame-store choice for a method about to be elevated, or null to take the
     * {@code -Dasync3.frame} default. The seam for docs/DESIGN.md §9 phase 5: a future policy keys
     * on what this profiler observes (e.g. live-set size, suspension frequency) to return
     * {@code "typed-fields"} / {@code "array-live"} / {@code "array-spill"} per hot method.
     * {@link Elevation} passes the result to {@code AsyncTransformer.transformMethodElevated}.
     */
    public static String preferredStore(String methodKey) {
        return null; // default policy: honor the global property
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
