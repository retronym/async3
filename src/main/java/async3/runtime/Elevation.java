package async3.runtime;

import async3.transform.AsyncTransformer;

import java.io.InputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * "Strategy B" elevation through virtual dispatch: instead of declaring a parallel {@code g$async}
 * on the callee hierarchy, the <em>call site</em> resolves the suspending entry, per actual
 * receiver class, at runtime.
 *
 * <p>{@link AsyncTransformer} rewrites a virtually dispatched call {@code g(args)} that it cannot
 * statically bind into
 * <pre>{@code invokedynamic g (receiver, args) -> CompletableFuture  [bootstrap, blockingDesc]}</pre>
 * followed by the usual {@code await} + coercion. The {@link #bootstrap} returns a call site whose
 * target, given the receiver, finds that receiver's <em>actual</em> {@code g} (honouring overrides
 * and inherited interface defaults), transforms just that method into a state machine via
 * {@link AsyncTransformer#transformMethod}, defines it as a hidden {@code NESTMATE} of the body's
 * declaring class, and invokes it — caching one invoker per receiver class (an inline cache).
 *
 * <p>This is sound under overriding precisely because resolution is against the runtime receiver,
 * not a static type: an override that does not await transforms to a state machine with no
 * suspension points (it completes synchronously, like a blocking shim), while a suspendable body
 * really suspends — each running its <em>own</em> {@code g}, never the wrong one. The callee
 * hierarchy is untouched (no {@code g$async} members, no annotations). Limitation: the per-receiver
 * transform is single-method (it elevates {@code g}'s own awaits, not transitive blocking inside
 * {@code g}); that is the same scope as {@link Async#lift}, and a deeper closure is future work.
 */
public final class Elevation {
    private Elevation() {}

    private static final MethodHandle START;
    private static final MethodHandle DISPATCH;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            START = l.findVirtual(FutureStateMachine.class, "start",
                    MethodType.methodType(CompletableFuture.class));
            DISPATCH = l.findVirtual(CallCtx.class, "dispatch",
                    MethodType.methodType(CompletableFuture.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * indy bootstrap. {@code invokedName} is the target method's name; {@code blockingDesc} is its
     * original (blocking) descriptor. {@code type} is {@code (receiver, args...) -> CompletableFuture}.
     */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String invokedName, MethodType type,
                                     String blockingDesc) {
        MethodHandle disp = DISPATCH.bindTo(new CallCtx(caller, invokedName, blockingDesc));
        // (Object[] all) -> CF  ==>  (receiver, args...) -> CF, collecting every argument into all
        MethodHandle collect = disp.asCollector(Object[].class, type.parameterCount());
        return new ConstantCallSite(collect.asType(type));
    }

    private static final class CallCtx {
        private final MethodHandles.Lookup caller;
        private final String name;
        private final String blockingDesc;
        private final ClassValue<MethodHandle> perReceiver = new ClassValue<>() {
            @Override protected MethodHandle computeValue(Class<?> rc) { return buildInvoker(rc); }
        };

        CallCtx(MethodHandles.Lookup caller, String name, String blockingDesc) {
            this.caller = caller;
            this.name = name;
            this.blockingDesc = blockingDesc;
        }

        /** {@code all = [receiver, args...]}; dispatch on the receiver's runtime class. */
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> dispatch(Object[] all) throws Throwable {
            return (CompletableFuture<Object>) perReceiver.get(all[0].getClass()).invoke(all);
        }

        /** Build, once per receiver class, an {@code (Object[] all) -> CompletableFuture} invoker. */
        private MethodHandle buildInvoker(Class<?> rc) {
            try {
                ClassLoader loader = rc.getClassLoader();
                MethodType blocking = MethodType.fromMethodDescriptorString(blockingDesc, loader);
                Method target = rc.getMethod(name, blocking.parameterArray());
                Class<?> decl = target.getDeclaringClass();   // where the body to transform lives

                byte[] declBytes;
                try (InputStream in = loader.getResourceAsStream(decl.getName().replace('.', '/') + ".class")) {
                    if (in == null) throw new IllegalStateException("cannot read bytecode of " + decl);
                    declBytes = in.readAllBytes();
                }
                AsyncTransformer.SingleMethod sm = AsyncTransformer.transformMethod(declBytes, name, blockingDesc);

                MethodHandles.Lookup smLookup = MethodHandles.privateLookupIn(decl, caller)
                        .defineHiddenClass(sm.bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
                MethodType ctorType = MethodType.fromMethodDescriptorString(sm.constructorDescriptor, loader);
                MethodHandle ctor = smLookup.findConstructor(smLookup.lookupClass(), ctorType);
                // (decl, args...) -> CF, then spread [receiver, args...] from one Object[]
                MethodHandle inv = MethodHandles.filterReturnValue(
                        ctor.asType(ctor.type().changeReturnType(FutureStateMachine.class)), START);
                return inv.asType(inv.type().generic()).asSpreader(Object[].class, inv.type().parameterCount());
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "elevation failed for " + rc.getName() + "." + name + blockingDesc, t);
            }
        }
    }
}
