package async3;

import async3.samples.NewSinkSamples;
import async3.samples.Samples;
import async3.transform.AsyncTransformer;
import async3.transform.InMemoryClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static async3.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code array-live} frame store (docs/DESIGN.md §9): the local's home is the two-array frame,
 * read/written in place via {@link async3.runtime.Frames}, so a suspension spills only the operand
 * stack. Selected by {@code -Dasync3.frame=array-live}; held to the same observable semantics as
 * the default {@code array-spill} store — the verification spine is the same blocking ≡ transformed
 * matrix, here run under the property.
 */
class ArrayLiveStoreTest {

    String previous;

    @BeforeEach
    void selectArrayLive() {
        previous = System.getProperty("async3.frame");
        System.setProperty("async3.frame", "array-live");
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty("async3.frame");
        else System.setProperty("async3.frame", previous);
    }

    static Class<?> loadInPlace(Class<?> source) throws Exception {
        byte[] out = AsyncTransformer.transformInPlace(classBytes(source));
        assertNotNull(out, "expected markers in " + source);
        return Class.forName(source.getName(), true,
                new InMemoryClassLoader(Map.of(source.getName(), out), ArrayLiveStoreTest.class.getClassLoader()));
    }

    @Test
    void semanticsMatchAcrossTheMatrix() throws Throwable {
        Class<?> s = loadInPlace(Samples.class);
        // fast path (already-complete) and real suspension (completed later) must agree, with
        // locals living in the frame across every await: ints, Strings, deep stacks, loops,
        // two-slot prims, try/catch, dead/null refs.
        assertEquals(43, invokeAsync(s, "addOne", done(42)));
        assertEquals("s:10", invokeAsync(s, "sumTwice", done(5), later("s")));
        assertEquals(32, invokeAsync(s, "deepStack", later(5), later(7)));
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<Integer>[] fs =
                new java.util.concurrent.CompletableFuture[]{done(4), later(5), done(6)};
        assertEquals(15, invokeAsync(s, "loopSum", (Object) fs));
        assertEquals("caught:boom", invokeAsync(s, "tryCatch",
                TestSupport.<Integer>laterFailed(new IllegalStateException("boom"))));
        assertEquals(33.0d, invokeAsync(s, "mixedPrims", later(2.5d), 4L));   // long + double + float locals
        assertEquals("pos:8", invokeAsync(s, "nullLocal", later(4)));         // ref local null then assigned
        assertEquals("v=5", invokeAsync(s, "initializedAcrossAwait", later(5)));
        assertEquals("15:7", invokeAsync(s, "deadRef", later(5), later(7)));  // dead ref nulled, not pinned

        Class<?> ns = loadInPlace(NewSinkSamples.class);
        assertEquals("Box(Box(5))", invokeAsync(ns, "nested", later(5)));     // NEW-sinking + array-live

        // the blocking tier (original method) is untouched regardless of store
        assertEquals("s:10", invoke(s, "sumTwice", done(5), done("s")));
    }
}
