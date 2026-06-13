package async3;

import async3.runtime.Profiler;
import async3.samples.ChainSamples;
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
 * Chain elevation (§7.7 witness) + deepened runtime transform (§7.9). A three-level virtual chain
 * {@code top → mid → leaf} where only {@code leaf} awaits. Under load the witness counts the whole
 * contiguous blocking chain — so {@code mid}'s call site goes hot too, not just {@code leaf} — and
 * the state machine built for {@code mid} elevates its own call to {@code leaf}. The result: a call
 * that previously blocked through both levels now suspends through both, via per-receiver call sites
 * resolved on demand.
 */
class ChainElevationTest {

    static final String LEAF = "async3.samples.ChainSamples.leaf(Ljava/util/concurrent/CompletableFuture;)I";
    static final String MID  = "async3.samples.ChainSamples.mid(Ljava/util/concurrent/CompletableFuture;)I";

    static Class<?> impl;

    @BeforeAll
    static void load() throws Exception {
        byte[] iface = AsyncTransformer.transformInPlace(classBytes(ChainSamples.class));
        Map<String, byte[]> classes = Map.of(
                ChainSamples.class.getName(), iface,
                ChainSamples.Impl.class.getName(), classBytes(ChainSamples.Impl.class));
        impl = Class.forName(ChainSamples.Impl.class.getName(), true,
                new InMemoryClassLoader(classes, ChainElevationTest.class.getClassLoader()));
    }

    @BeforeEach
    void freshProfiler() {
        Profiler.reset();
        Profiler.setThreshold(2);
    }

    @AfterAll
    static void restore() {
        Profiler.reset();
        Profiler.setThreshold(8);
    }

    static CompletableFuture<?> topAsync(Object inst, CompletableFuture<Integer> f) throws Throwable {
        Method m = inst.getClass().getMethod("top$async", CompletableFuture.class);
        return (CompletableFuture<?>) m.invoke(inst, f);
    }

    @Test
    void elevatesTheWholeBlockingChain() throws Throwable {
        Object inst = newInstance(impl);

        // Tier 0: top → mid → leaf → await blocks. The witness counts the contiguous blocking
        // chain [leaf, mid] each time, so both climb toward hot — not just the leaf.
        for (int i = 0; i < Profiler.threshold(); i++)
            assertEquals(112, topAsync(inst, later(5)).get(2, TimeUnit.SECONDS));   // ((5+1)*2)+100
        assertTrue(Profiler.isHot(LEAF), "leaf hot");
        assertTrue(Profiler.isHot(MID), "mid hot too — chain elevation counts the caller, not just the awaiter");
        assertEquals(0, Profiler.elevations(), "still tier 0 — nothing flipped during warmup");

        // The next call, with a future that never completes on its own, must suspend through BOTH
        // levels: mid's site flips, and mid's state machine elevates its own leaf call, which flips
        // too. A single un-deepened flip would still block at leaf — the preemptive timeout guards it.
        CompletableFuture<Integer> gate = new CompletableFuture<>();
        CompletableFuture<?> result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> topAsync(inst, gate));

        assertFalse(result.isDone(), "fully suspended through mid and leaf — caller not blocked");
        assertEquals(2, Profiler.elevations(), "two sites flipped on this call: mid, then leaf");

        gate.complete(5);                                    // fire the deepest awaited future
        assertEquals(112, result.get(2, TimeUnit.SECONDS));  // resumes top ← mid ← leaf to the same answer
    }
}
