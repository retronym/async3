package async3;

import async3.samples.NewSinkSamples;
import async3.samples.Samples;
import async3.transform.AsyncTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

import static async3.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code typed-fields} frame store (docs/DESIGN.md §9): each cross-suspension value lives in a
 * precisely-typed field of the generated state-machine class (the scala-async 2.0 {@code UseFields}
 * shape), read/written in place — primitives in their own typed fields (no long-normalization),
 * references in {@code Object} fields. Honored where a class is generated (class-per-method here);
 * the agent's shared shell downgrades to an array store. Held to the same blocking ≡ transformed
 * matrix as the other stores.
 */
class TypedFieldsStoreTest {

    String previous;

    @BeforeEach
    void selectTypedFields() {
        previous = System.getProperty("async3.frame");
        System.setProperty("async3.frame", "typed-fields");
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty("async3.frame");
        else System.setProperty("async3.frame", previous);
    }

    @Test
    void semanticsMatchAcrossTheMatrix() throws Throwable {
        Class<?> s = transformAndLoad(Samples.class);
        assertEquals(43, invokeAsync(s, "addOne", done(42)));
        assertEquals("s:10", invokeAsync(s, "sumTwice", done(5), later("s")));
        assertEquals(32, invokeAsync(s, "deepStack", later(5), later(7)));
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<Integer>[] fs =
                new java.util.concurrent.CompletableFuture[]{done(4), later(5), done(6)};
        assertEquals(15, invokeAsync(s, "loopSum", (Object) fs));
        assertEquals("caught:boom", invokeAsync(s, "tryCatch",
                TestSupport.<Integer>laterFailed(new IllegalStateException("boom"))));
        assertEquals(33.0d, invokeAsync(s, "mixedPrims", later(2.5d), 4L));   // long/double/float typed fields
        assertEquals("pos:8", invokeAsync(s, "nullLocal", later(4)));
        assertEquals("v=5", invokeAsync(s, "initializedAcrossAwait", later(5)));
        assertEquals("15:7", invokeAsync(s, "deadRef", later(5), later(7)));  // dead ref field nulled

        Class<?> ns = transformAndLoad(NewSinkSamples.class);
        assertEquals("Box(Box(5))", invokeAsync(ns, "nested", later(5)));
    }

    @Test
    void emitsTypedInstanceFields() {
        AsyncTransformer.Result r = AsyncTransformer.transform(classBytes(Samples.class));
        // sumTwice carries an int (x) and a String (s) across awaits → an int field and an Object field
        Map.Entry<String, byte[]> sm = r.stateMachines.entrySet().stream()
                .filter(e -> e.getKey().contains("sumTwice")).findFirst().orElseThrow();
        ClassNode cn = new ClassNode();
        new ClassReader(sm.getValue()).accept(cn, 0);
        boolean intField = cn.fields.stream().anyMatch(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("I"));
        boolean refField = cn.fields.stream().anyMatch(f ->
                (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("Ljava/lang/Object;"));
        assertTrue(intField, "expected a precisely-typed int field (no long-normalization)");
        assertTrue(refField, "expected an Object field for the reference local");
    }
}
