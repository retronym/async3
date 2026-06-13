package async3;

import async3.samples.DispatchSamples;
import async3.samples.IfaceSamples;
import async3.samples.ProtectedSamples;
import async3.transform.AsyncTransformer;
import async3.transform.InMemoryClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static async3.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Elevation through virtual dispatch via Strategy B ({@link async3.runtime.Elevation}): a virtually
 * dispatched call to a suspendable sibling is rewritten to an {@code invokedynamic} that resolves
 * the suspending entry against the actual receiver at runtime. Sound under overriding, with no
 * {@code $async} scaffolding on the callee hierarchy. Statically bound calls keep the direct
 * {@code g$async} path.
 */
class ElevateDispatchTest {

    static ClassNode transformedNode(Class<?> source) {
        byte[] out = AsyncTransformer.transformInPlace(classBytes(source));
        assertNotNull(out, "expected markers in " + source);
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        return cn;
    }

    static boolean hasMethod(ClassNode cn, String name) {
        return cn.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    static Object asyncVia(Class<?> host, Object target, String name, Object... args) throws Throwable {
        // getMethod (unlike getDeclaredMethods) resolves inherited default $async siblings
        Method m = host.getMethod(name + "$async", paramTypes(args));
        CompletableFuture<?> f = (CompletableFuture<?>) m.invoke(target, args);
        return f.get(5, TimeUnit.SECONDS);
    }

    static Object blockingVia(Class<?> host, Object target, String name, Object... args) throws Throwable {
        return host.getMethod(name, paramTypes(args)).invoke(target, args);
    }

    static Class<?>[] paramTypes(Object[] args) {
        Class<?>[] t = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) t[i] = CompletableFuture.class; // all sample params are futures
        return t;
    }

    /** Statically bound (final) callee uses the direct path; overridable (virtual) uses Strategy B. */
    @Test
    void bothFinalAndVirtualCallsAreElevatedAndSound() throws Throwable {
        ClassNode cn = transformedNode(DispatchSamples.class);
        assertTrue(hasMethod(cn, "vleaf$async"));
        assertTrue(hasMethod(cn, "fleaf$async"));
        assertTrue(hasMethod(cn, "callsFinal$async"),   "final-dispatched caller elevates directly");
        assertTrue(hasMethod(cn, "callsVirtual$async"), "virtual caller elevates via Strategy B");

        byte[] out = AsyncTransformer.transformInPlace(classBytes(DispatchSamples.class));
        Class<?> c = Class.forName(DispatchSamples.class.getName(), true,
                new InMemoryClassLoader(Map.of(DispatchSamples.class.getName(), out),
                        ElevateDispatchTest.class.getClassLoader()));
        Object inst = newInstance(c, 100);                                // seed = 100
        assertEquals(210, blockingVia(c, inst, "callsFinal", done(5)));   // (5 + 100) * 2
        assertEquals(210, asyncVia(c, inst, "callsFinal", later(5)));
        assertEquals(210, blockingVia(c, inst, "callsVirtual", done(5)));
        assertEquals(210, asyncVia(c, inst, "callsVirtual", later(5)));   // elevated through vleaf, same answer
    }

    /**
     * The headline soundness case. {@code indirect} elevates its {@code INVOKEINTERFACE leaf} via
     * Strategy B; on a receiver that inherits the awaiting default it suspends, on one that
     * overrides {@code leaf} it runs the override — each matching its blocking tier (the bug the
     * static path had to refuse).
     */
    @Test
    void interfaceCallElevatesPerActualReceiver() throws Throwable {
        ClassNode cn = transformedNode(IfaceSamples.class);
        assertTrue(hasMethod(cn, "leaf$async"));
        assertTrue(hasMethod(cn, "indirect$async"), "interface default-to-default elevates via Strategy B");

        byte[] iface = AsyncTransformer.transformInPlace(classBytes(IfaceSamples.class));
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(IfaceSamples.class.getName(), iface);
        classes.put(IfaceSamples.Impl.class.getName(), classBytes(IfaceSamples.Impl.class));
        classes.put(IfaceSamples.OverridingImpl.class.getName(), classBytes(IfaceSamples.OverridingImpl.class));
        ClassLoader loader = new InMemoryClassLoader(classes, ElevateDispatchTest.class.getClassLoader());

        Class<?> impl = Class.forName(IfaceSamples.Impl.class.getName(), true, loader);
        Object i = newInstance(impl);
        assertEquals(60, blockingVia(impl, i, "indirect", done(5)));        // (await(5)+1) * 10
        assertEquals(60, asyncVia(impl, i, "indirect", later(5)));          // resolves the default leaf (see DynamicElevationTest for the tier flip)

        Class<?> ovr = Class.forName(IfaceSamples.OverridingImpl.class.getName(), true, loader);
        Object o = newInstance(ovr);
        assertEquals(9990, blockingVia(ovr, o, "indirect", done(5)));       // 999 * 10 — override ignores f
        assertEquals(9990, asyncVia(ovr, o, "indirect", later(5)));         // Strategy B runs the override, not the default
    }

    /**
     * Hardening: the elevated callee may not be public. A {@code protected} overridable target is
     * dispatched virtually (Strategy B); the call site must resolve it (getMethod misses it →
     * getDeclaredMethod hierarchy walk + setAccessible), on both the blocking and suspending tiers.
     */
    @Test
    void protectedVirtualTargetResolves() throws Throwable {
        byte[] out = AsyncTransformer.transformInPlace(classBytes(ProtectedSamples.class));
        assertNotNull(out);
        Class<?> c = Class.forName(ProtectedSamples.class.getName(), true,
                new InMemoryClassLoader(Map.of(ProtectedSamples.class.getName(), out),
                        ElevateDispatchTest.class.getClassLoader()));
        Object inst = newInstance(c);
        assertEquals(18, blockingVia(c, inst, "caller", done(5)));      // (5 + 1) * 3
        assertEquals(18, asyncVia(c, inst, "caller", later(5)));        // elevated path resolves the protected leaf
    }
}
