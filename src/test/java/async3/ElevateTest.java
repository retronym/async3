package async3;

import async3.samples.ElevateSamples;
import async3.transform.AsyncTransformer;
import async3.transform.InMemoryClassLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

import static async3.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.7 transitive elevation. A method that blocks only because it calls a suspendable method —
 * with no {@code await} of its own — still gets a suspending {@code $async} sibling, whose
 * suspension point is the call to the callee's {@code $async} entry ({@code invoke g} rewritten
 * to {@code await(g$async)}). Exercised through the in-place (agent) shape, the same way
 * {@link AgentShapeTest} drives the direct-await case.
 */
class ElevateTest {

    static byte[] out;
    static Class<?> samples;

    @BeforeAll
    static void transform() throws Exception {
        out = AsyncTransformer.transformInPlace(classBytes(ElevateSamples.class));
        assertNotNull(out, "expected markers in ElevateSamples");
        samples = Class.forName(ElevateSamples.class.getName(), true,
                new InMemoryClassLoader(Map.of(ElevateSamples.class.getName(), out),
                        ElevateTest.class.getClassLoader()));
    }

    /** The headline: a method with no direct await still receives the suspending pair. */
    @Test
    void indirectMethodsGetTheAsyncPair() {
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        for (String name : new String[] {"indirect", "twoLevel", "mixed", "greetLen", "viaVoid"}) {
            assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals(name + "$async")),
                    "no suspending sibling for indirectly-blocking " + name);
            assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals(name + "$asyncBody")),
                    "no resumable body for indirectly-blocking " + name);
        }
        // idempotent under agent re-entry
        assertNull(AsyncTransformer.transformInPlace(out));
    }

    /** Fast path (already-completed futures) and real suspension (completed later) must agree. */
    @Test
    void primitiveIndirection() throws Throwable {
        assertEquals(60, invokeAsync(samples, "indirect", done(5)));
        assertEquals(60, invokeAsync(samples, "indirect", later(5)));
        assertEquals(67, invokeAsync(samples, "twoLevel", done(5)));
        assertEquals(67, invokeAsync(samples, "twoLevel", later(5)));
    }

    @Test
    void mixedDirectAndIndirectAwaits() throws Throwable {
        assertEquals("3:5", invokeAsync(samples, "mixed", done(3), done(4)));
        assertEquals("3:5", invokeAsync(samples, "mixed", later(3), later(4)));
    }

    @Test
    void objectAndVoidCoercions() throws Throwable {
        assertEquals(6, invokeAsync(samples, "greetLen", done("bob")));   // "hi bob".length()
        assertEquals(6, invokeAsync(samples, "greetLen", later("bob")));
        assertEquals(5, invokeAsync(samples, "viaVoid", done(2), done(3)));
        assertEquals(5, invokeAsync(samples, "viaVoid", later(2), later(3)));
    }

    /** The blocking tier is untouched and agrees with the elevated suspending tier. */
    @Test
    void blockingTierStillWorks() throws Throwable {
        assertEquals(60, invoke(samples, "indirect", done(5)));
        assertEquals("3:5", invoke(samples, "mixed", done(3), done(4)));
        assertEquals(6, invoke(samples, "greetLen", done("bob")));
    }
}
