package async3;

import async3.samples.DispatchSamples;
import async3.samples.IfaceSamples;
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
 * Elevation soundness across dispatch. {@code invoke g} → {@code await(g$async)} is only correct
 * when {@code g} is statically bound; a virtually dispatched callee may be overridden without a
 * matching {@code g$async}, so callers through it are left blocking instead. Guards
 * {@code AsyncTransformer.dispatchSafe} against regression.
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

    @Test
    void virtualCallIsNotElevatedButFinalCallIs() throws Throwable {
        ClassNode cn = transformedNode(DispatchSamples.class);

        // direct awaiters always get their own pair, regardless of how they are dispatched
        assertTrue(hasMethod(cn, "vleaf$async"));
        assertTrue(hasMethod(cn, "fleaf$async"));

        // caller through a final (statically bound) callee: elevated
        assertTrue(hasMethod(cn, "callsFinal$async"), "final-dispatched callee should elevate the caller");
        // caller through an overridable (virtual) callee: left blocking — no unsound entry
        assertFalse(hasMethod(cn, "callsVirtual$async"), "virtual dispatch must not be elevated");

        // and the elevated path is semantically faithful to the blocking tier
        byte[] out = AsyncTransformer.transformInPlace(classBytes(DispatchSamples.class));
        Class<?> c = Class.forName(DispatchSamples.class.getName(), true,
                new InMemoryClassLoader(Map.of(DispatchSamples.class.getName(), out),
                        ElevateDispatchTest.class.getClassLoader()));
        Object inst = newInstance(c, 100);                       // seed = 100
        assertEquals(210, invokeOn(c, inst, "callsFinal", done(5)));        // (5 + 100) * 2
        assertEquals(210, invokeAsyncOn(inst, "callsFinal", later(5)));     // suspends, same answer
    }

    @Test
    void interfaceDefaultToDefaultIsNotElevated() throws Throwable {
        ClassNode cn = transformedNode(IfaceSamples.class);

        // leaf awaits directly → sound to pair; indirect reaches suspension only through an
        // INVOKEINTERFACE call, which is not statically bound → must not be elevated.
        assertTrue(hasMethod(cn, "leaf$async"), "direct awaiter should still get its pair");
        assertFalse(hasMethod(cn, "indirect$async"), "INVOKEINTERFACE dispatch must not be elevated");

        // the direct awaiter still works on a non-overriding implementer
        byte[] iface = AsyncTransformer.transformInPlace(classBytes(IfaceSamples.class));
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(IfaceSamples.class.getName(), iface);
        classes.put(IfaceSamples.Impl.class.getName(), classBytes(IfaceSamples.Impl.class));
        ClassLoader loader = new InMemoryClassLoader(classes, ElevateDispatchTest.class.getClassLoader());
        Class<?> impl = Class.forName(IfaceSamples.Impl.class.getName(), true, loader);
        Object inst = newInstance(impl);
        // getMethod (unlike getDeclaredMethods) resolves the inherited default sibling
        Method async = impl.getMethod("leaf$async", CompletableFuture.class);
        Object f = async.invoke(inst, later(5));
        assertEquals(6, ((CompletableFuture<?>) f).get(5, TimeUnit.SECONDS));   // await(5) + 1
        assertEquals(6, impl.getMethod("leaf", CompletableFuture.class).invoke(inst, done(5)));
    }
}
