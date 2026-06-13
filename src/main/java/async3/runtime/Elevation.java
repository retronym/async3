package async3.runtime;

import async3.transform.AsyncTransformer;

import java.io.InputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
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
 *
 * <p><b>Dynamic tier flip.</b> Each per-receiver {@code Site} starts on the <em>blocking</em> tier:
 * it just invokes the real {@code g} (whose own awaits block the carrier thread), so the
 * state-machine cost is not paid until it is worth paying. {@link Profiler}, fed by the blocking
 * {@link AsyncRT#await}, witnesses which methods actually block; when {@code g} crosses the hot
 * threshold the {@code Site} flips to the suspending transform on its next call. The two tiers are
 * result-equivalent — flipping only changes whether a not-yet-complete await parks the thread or
 * releases it. This is the userland-Loom tiering of docs/DESIGN.md §7.7, here keyed to the call
 * site; there is no on-stack replacement, so a call already blocked stays blocked and only
 * subsequent calls suspend.
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
        private final ClassValue<Site> perReceiver = new ClassValue<>() {
            @Override protected Site computeValue(Class<?> rc) { return new Site(rc); }
        };

        CallCtx(MethodHandles.Lookup caller, String name, String blockingDesc) {
            this.caller = caller;
            this.name = name;
            this.blockingDesc = blockingDesc;
        }

        /** {@code all = [receiver, args...]}; dispatch on the receiver's runtime class. */
        CompletableFuture<Object> dispatch(Object[] all) throws Throwable {
            return perReceiver.get(all[0].getClass()).invoke(all);
        }

        /**
         * One per receiver class: starts on the blocking tier and flips to a suspending state
         * machine the first time the {@link Profiler} reports its target method hot. The two tiers
         * are result-equivalent; the flip only changes whether a not-yet-complete await parks the
         * carrier thread (blocking) or releases it (suspending).
         */
        private final class Site {
            private final String key;            // Profiler key: class.name+descriptor
            private final Method target;         // the actual resolved method on this receiver class
            private final Class<?> decl;         // where the body to transform/run lives
            private volatile MethodHandle suspending;
            private volatile boolean elevated;

            Site(Class<?> rc) {
                try {
                    MethodType blocking = MethodType.fromMethodDescriptorString(blockingDesc, rc.getClassLoader());
                    this.target = resolve(rc, name, blocking.parameterArray());
                    this.decl = target.getDeclaringClass();
                    this.key = decl.getName() + "." + name + blockingDesc;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot resolve " + rc.getName() + "." + name + blockingDesc, e);
                }
            }

            @SuppressWarnings("unchecked")
            CompletableFuture<Object> invoke(Object[] all) throws Throwable {
                if (!elevated && Profiler.isHot(key)) elevate();
                if (elevated) return (CompletableFuture<Object>) suspending.invoke(all);
                return blockingInvoke(all);
            }

            /** Tier 0: run the real method on the calling thread; its own awaits block (and profile). */
            private CompletableFuture<Object> blockingInvoke(Object[] all) {
                try {
                    Object result = target.invoke(all[0], Arrays.copyOfRange(all, 1, all.length));
                    return CompletableFuture.completedFuture(result);
                } catch (InvocationTargetException e) {
                    CompletableFuture<Object> f = new CompletableFuture<>();
                    f.completeExceptionally(e.getCause());   // failed future == await rethrow semantics
                    return f;
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }

            private synchronized void elevate() {
                if (elevated) return;
                suspending = buildSuspending(decl);
                elevated = true;
                Profiler.recordElevation();
            }
        }

        /**
         * Resolve the actual method this receiver runs. {@code getMethod} covers public methods
         * (including inherited and interface defaults); the fallback walks the superclass chain
         * with {@code getDeclaredMethod} so a {@code protected}/package-private overridable target
         * still resolves (and is made accessible for the blocking-tier reflective invoke).
         */
        private Method resolve(Class<?> rc, String name, Class<?>[] ptypes) throws NoSuchMethodException {
            try {
                return rc.getMethod(name, ptypes);
            } catch (NoSuchMethodException publicMiss) {
                for (Class<?> c = rc; c != null; c = c.getSuperclass()) {
                    try {
                        Method m = c.getDeclaredMethod(name, ptypes);
                        m.setAccessible(true);
                        return m;
                    } catch (NoSuchMethodException ignore) { /* keep walking up */ }
                }
                throw publicMiss;
            }
        }

        /** Transform {@code decl}'s {@code name+blockingDesc} into a suspending {@code (Object[]) -> CF} invoker. */
        private MethodHandle buildSuspending(Class<?> decl) {
            try {
                ClassLoader loader = decl.getClassLoader();
                byte[] declBytes;
                try (InputStream in = loader.getResourceAsStream(decl.getName().replace('.', '/') + ".class")) {
                    if (in == null) throw new IllegalStateException("cannot read bytecode of " + decl);
                    declBytes = in.readAllBytes();
                }
                // Elevated transform: g's own virtually dispatched suspendable calls become
                // per-receiver call sites too, so suspension goes deeper than this one method.
                AsyncTransformer.SingleMethod sm = AsyncTransformer.transformMethodElevated(declBytes, name, blockingDesc);

                MethodHandles.Lookup smLookup = MethodHandles.privateLookupIn(decl, caller)
                        .defineHiddenClass(sm.bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
                MethodType ctorType = MethodType.fromMethodDescriptorString(sm.constructorDescriptor, loader);
                MethodHandle ctor = smLookup.findConstructor(smLookup.lookupClass(), ctorType);
                // (decl, args...) -> CF, then spread [receiver, args...] from one Object[]
                MethodHandle inv = MethodHandles.filterReturnValue(
                        ctor.asType(ctor.type().changeReturnType(FutureStateMachine.class)), START);
                return inv.asType(inv.type().generic()).asSpreader(Object[].class, inv.type().parameterCount());
            } catch (Throwable t) {
                throw new IllegalStateException("elevation failed for " + decl.getName() + "." + name + blockingDesc, t);
            }
        }
    }
}
