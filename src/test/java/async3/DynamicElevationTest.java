package async3;

import async3.runtime.Profiler;
import async3.samples.IfaceSamples;
import async3.transform.AsyncTransformer;
import async3.transform.InMemoryClassLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static async3.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * StackWalker-driven dynamic elevation (docs/DESIGN.md §7.7). A virtually elevated call site
 * ({@link async3.runtime.Elevation}) starts on the blocking tier; the blocking {@code AsyncRT.await}
 * witnesses each real block via {@link Profiler}, and once the awaiting method is hot the site flips
 * itself to a suspending state machine — proven by the same call that blocked before now returning
 * a suspended (incomplete) future instead of parking the caller.
 */
class DynamicElevationTest {

    /** Profiler key the witness attributes blocks to: {@code class.name+descriptor}. */
    static final String LEAF_KEY = "async3.samples.IfaceSamples.leaf(Ljava/util/concurrent/CompletableFuture;)I";

    static Class<?> impl;

    @BeforeAll
    static void load() throws Exception {
        byte[] iface = AsyncTransformer.transformInPlace(classBytes(IfaceSamples.class));
        Map<String, byte[]> classes = Map.of(
                IfaceSamples.class.getName(), iface,
                IfaceSamples.Impl.class.getName(), classBytes(IfaceSamples.Impl.class));
        ClassLoader loader = new InMemoryClassLoader(classes, DynamicElevationTest.class.getClassLoader());
        impl = Class.forName(IfaceSamples.Impl.class.getName(), true, loader);
    }

    @BeforeEach
    void freshProfiler() {
        Profiler.reset();
        Profiler.setThreshold(3);
    }

    @AfterAll
    static void restore() {
        Profiler.reset();
        Profiler.setThreshold(8);
    }

    static CompletableFuture<?> indirectAsync(Object inst, CompletableFuture<Integer> f) throws Throwable {
        Method m = inst.getClass().getMethod("indirect$async", CompletableFuture.class);
        return (CompletableFuture<?>) m.invoke(inst, f);
    }

    @Test
    void hotBlockingInterfaceCallFlipsToSuspending() throws Throwable {
        Object inst = newInstance(impl);

        // Tier 0: each call runs the real interface `leaf`, which blocks on a soon-completing
        // future. The blocking await profiles itself (StackWalker → "leaf"), and the call returns
        // 60 once leaf's future fires. No state machine for leaf yet.
        for (int i = 0; i < Profiler.threshold(); i++) {
            assertFalse(Profiler.isHot(LEAF_KEY), "leaf is not hot until the threshold is crossed");
            assertEquals(60, indirectAsync(inst, later(5)).get(2, TimeUnit.SECONDS));
        }
        assertTrue(Profiler.isHot(LEAF_KEY), "leaf hot after " + Profiler.threshold() + " witnessed blocks");
        assertEquals(0, Profiler.elevations(), "still tier 0 — nothing has flipped yet");
        assertTrue(Profiler.blockingAwaits() >= Profiler.threshold());

        // The next call must SUSPEND, not block: hand it a future that never completes on its own.
        // A blocking-tier call would park here forever — the preemptive timeout is the safety net.
        CompletableFuture<Integer> gate = new CompletableFuture<>();
        CompletableFuture<?> result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> indirectAsync(inst, gate));

        assertFalse(result.isDone(), "the elevated call returned a suspended future — it did not block");
        assertEquals(1, Profiler.elevations(), "the hot site flipped exactly once");

        gate.complete(5);                                   // fire the awaited future
        assertEquals(60, result.get(2, TimeUnit.SECONDS));  // resumes to the same answer tier 0 gave
    }
}
