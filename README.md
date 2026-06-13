# async3

**async/await as a bytecode transform** — a standalone prototype exploring whether Scala's
tree-level `async` compiler phase (`scala.tools.nsc.transform.async.AsyncPhase`, with its ANF
transform, live-variable analysis, and `Lifter`/`UseFields` machinery) can be replaced by a
rewrite on bytecode, where most of that machinery has no reason to exist: locals are numbered
slots, pattern matches are already branches, `finally` is already duplicated, and the operand
stack is just more state to spill.

Plain Java + ASM, no dependency on the Scala build. Design rationale, prior art, and the
phased plan: [docs/DESIGN.md](docs/DESIGN.md).

```
mvn test                                              # 52 tests
mvn -q compile exec:java -Dexec.mainClass=async3.demo.Demo
```

## The idea in one method

You write an ordinary, synchronous method. `await` is a real static method whose default
implementation just blocks — so this code runs correctly, as-is, with no transformation
("tier 0"):

```java
public static String sumTwice(CompletableFuture<Integer> fa, CompletableFuture<String> fb) {
    int x = AsyncRT.await(fa);
    String s = AsyncRT.await(fb);
    return s + ":" + (x * 2);
}
```

The ASM transform leaves that method byte-identical and derives a suspending sibling next to
it. `javap` on the transformed class:

```
public static java.util.concurrent.CompletableFuture sumTwice$async(CompletableFuture, CompletableFuture);
  Code:
     0: new           #9    // class async3/samples/Samples$async$sumTwice$1
     3: dup
     4: aload_0
     5: aload_1
     6: invokespecial #168  // Method Samples$async$sumTwice$1."<init>":(...)V
     9: invokevirtual #169  // Method Samples$async$sumTwice$1.start:()Ljava/util/concurrent/CompletableFuture;
    12: areturn
```

The generated state machine's `apply` is the original method body, re-entrant. Dispatch is a
`tableswitch` on the state; each state re-loads its live frame from a generic two-array spill
(`Object[] refs` / `long[] prims` — no per-method field layout, same trick as Quasar/Kilim):

```
public void apply(java.lang.Object);
  Code:
       0: aload_0
       1: getfield      #26   // Field FutureStateMachine.state:I
       4: tableswitch   { 0: 32, 1: 55, 2: 78, default: 110 }
      ...
      // the await site itself: set next state, fast-path check, park if incomplete
     172: aload_3
     175: aload_0
     176: iconst_2
     177: putfield      #26   // state = 2
     183: invokevirtual #41   // getCompleted — fast path: skip suspension if already done
     186: dup
     187: ifnonnull     221
     190: ...
     199: lastore             // spill x — the only live value here — into prims[2]
     204: iconst_0
     205: aconst_null
     206: aastore             // fa and fb are dead past this point: null their ref slots, so
     212: aconst_null         // the suspended frame pins nothing the resumed code can't read
     213: aastore             // (liveness-driven, the analogue of scala-async's fieldsToNullOut)
     217: invokevirtual #45   // onComplete — park; resumes apply() when the future fires
     220: return
     221: astore_1            // resumption point: completed value lands here
```

The original `LocalVariableTable` is carried over and remapped, so a debugger sees source
names, not slots:

```
LocalVariableTable:
  Start  Length  Slot  Name   Signature
    118     134     2    fa   Ljava/util/concurrent/CompletableFuture;
    118     134     3    fb   Ljava/util/concurrent/CompletableFuture;
    172      80     4     x   I
    232      20     5     s   Ljava/lang/String;
```

Because the blocking version stays valid and the marker is just a method call, transformation
can be deferred to runtime and applied only where profiling says it matters — the tiered,
lazy variant sketched in the design doc.

## See it suspend

`Demo.main` runs six scenarios; scenario 2 completes the futures manually from the main
thread and renders the suspended machine between steps (via the `$asyncDebug` metadata the
transform emits per state):

```
== 2. real suspension, resumed by manual completion on the main thread
   Samples.sumTwice(...) suspended at state 1 (line 22): fb = CompletableFuture[Not completed]
   Samples.sumTwice(...) suspended at state 2 (line 23): x = 5
   result = s:10
```

The frame holds exactly what the resumed code can still read, under source names: at state 1
only `fb` (`fa` was consumed by the first await and its slot is already nulled); at state 2
only `x`. The demo also dumps every generated class to `target/transformed-classes/` for
`javap -c -l -p` spelunking.

## The Java agent: the default deployment

```
java -javaagent:target/async3-0.1-SNAPSHOT.jar -cp ... your.Main
```

At class load — the moment when adding methods is still legal, unlike retransformation — the
agent gives every marked method two siblings *in the host class itself*, with no class
generation per method at all. The resumable body becomes a private static sibling, and the
entry point binds the shared runtime shell to it via an `LDC MethodHandle` constant:

```
private static void sumTwice$asyncBody(async3.runtime.FutureStateMachine, java.lang.Object);

public static java.util.concurrent.CompletableFuture sumTwice$async(CompletableFuture, CompletableFuture);
  Code:
     0: new           #194  // class async3/runtime/DelegatingStateMachine
     ...
     8: ldc           #216  // MethodHandle REF_invokeStatic Samples.sumTwice$asyncBody:(LFutureStateMachine;Ljava/lang/Object;)V
    10: ldc           #218  // String "state 1 (line 22): fb -> refs[1] ... state 2 (line 23): x -> prims[2] (I)"
    12: invokespecial #202  // DelegatingStateMachine.<init>
    ...
    31: invokevirtual #206  // start
```

Consequences: private access is same-class access (no nestmate machinery), and — the
headline — **the executing bytecode lives in the class the source lines belong to**, so IDE
line breakpoints inside lambda bodies and transformed methods bind and fire naturally.
Running the probe under the agent:

```
$ java -javaagent:target/async3-0.1-SNAPSHOT.jar -cp ... async3.AgentProbe
agent entry present: addOne$async
addOne$async -> 42
executing in async3.AgentProbe.lambda$main$3db80bdd$1$asyncBody
iter 0 -> 42
```

Verified end-to-end by `JdiAgentCheck`, which attaches over JDI, replays IntelliJ's exact
breakpoint procedure (`classesByName` → `locationsOfLine` → breakpoint), and observes the hit
landing in the `$asyncBody` sibling — in the host class, named locals intact. A constant-pool
byte scan pre-filters untouched classes, so the agent is cheap to leave on; it applies to
every marked class on the classpath, including code you didn't build, and is idempotent with
AoT-prepared classes.

## The user-facing API

```java
// async: run a block with awaits in it
CompletableFuture<Integer> sum = Async.async(() -> {
    int x = Async.await(la);
    int y = Async.await(lb);
    return x + y;
});

// lift: derive the suspending variant of an existing, ordinary blocking method
var sumTwice = Async.lift(Samples::sumTwice);
CompletableFuture<String> r = sumTwice.apply(fa, fb);
```

Under the agent, both simply bind the prepared `m$async` entry — no work at runtime beyond a
method-handle lookup. `lift` covers every reference shape through one mechanism (captured and
applied arguments concatenate into the target's entry locals): static, unbound instance
(`C::m`), bound instance (`obj::m`), private targets, plain parameterized lambdas. It is the
runtime analogue of the annotation-driven (Optimus-style) frontend's direct/`$queued` method
pair (design doc [§4](docs/DESIGN.md#4-the-annotation-driven-frontend-optimus-style),
[§7.5](docs/DESIGN.md#75-lambda-front-end-prototype-of-the-runtime-triggered-transform)).

**No agent? They still work.** Both APIs fall back to transforming at runtime by *cracking*
the (serializable) lambda to find the `lambda$...` impl method javac generated, defining the
state machine as a hidden nestmate of the capturing class. The mechanics, and the IntelliJ
breakpoint-binding limitation of this path (plus its `-Dasync3.lambda.debuggable=true`
workaround), are in the design doc's
[§7.5](docs/DESIGN.md#75-lambda-front-end-prototype-of-the-runtime-triggered-transform);
under the agent the issue doesn't exist.

Cost model: resolution is a two-tier chain, cached per impl method. The first use of each
lambda probes for the prepared `m$async` entry (one reflective lookup); only if absent does
the fallback read the capturing class's bytecode back from the loader, parse it, transform
the one method, and define the state machine — so a class with N async lambdas is parsed N
times without the agent, once per lambda, first use only. The agent inverts this: one parse
per class at load time prepares all N methods, and the runtime tier never reads bytecode at
all.

## Debugging in IntelliJ

1. `mvn -q -DskipTests package`, then add `-javaagent:target/async3-0.1-SNAPSHOT.jar` to the
   run configuration's VM options.
2. Open `async3.demo.Demo`, set line breakpoints anywhere — inside the scenario-0 lambda,
   inside [`Samples.sumTwice`](src/main/java/async3/samples/Samples.java) — and **Debug
   `main()`**. Scenario 2 gives a linear, single-threaded story: first hit in state 0; after
   `fa.complete(5)` the next line's breakpoint hits *inside the resumed state machine* with
   `x = 5` under its source name in the Variables view.

What to expect while stepping:

- `this` in transformed frames is the state machine; expand `refs`/`prims` for the raw
  spilled frame, or evaluate `async3.runtime.AsyncDebug.describe(this)` to get
  `...sumTwice(...) suspended at state 2 (line 23): fa = ..., x = 5`.
- **Stepping over an await that actually suspends steps out of the method** — `apply` parks
  and returns. That is faithful to what the machine does; put a breakpoint on the line after
  the await to follow the logical flow — the same experience as Kotlin coroutines without
  their debugger plugin, and closed the same way (see [Future work](#future-work)).

## How it compares

| System | Transform level | Suspension mechanism | vs. async3 |
|---|---|---|---|
| **Project Loom** (JDK 21+) | VM-internal (native stack copying) | Block on a virtual thread | Zero code changes, but JDK 21+ only; pinning in `synchronized`; collapses fork/join into sequential blocking — Optimus-style graph schedulers lose the dataflow graph |
| **Kotlin coroutines** | Compiler backend (bytecode-level IR) | CPS; spills to typed fields of a generated `Continuation` class | Closest relative. Kotlin generates a class per coroutine; async3's generic two-array frame works identically at compile time and at runtime without per-method class generation |
| **scala-async / `AsyncPhase`** | Compiler, on typed trees | ANF + lifting into a state-machine class | The predecessor this project aims to replace. The tree-level complexity (ANF, symbol re-ownership, patmat interaction, value-class boxing) disappears at bytecode level |
| **Quasar** | Agent / AoT ASM | Generic `Stack` (`long[]` + `Object[]`) | Same frame representation. Quasar is unmaintained and agent/AoT-only; async3 adds the runtime-triggered path (cracking + `defineHiddenClass`) and profile-driven tiering |
| **Kilim / Javaflow** | AoT ASM | Stack capture via rewriting | Same family; Javaflow documents the uninitialized-object problem async3 solves with NEW-sinking |

The design doc's [§7](docs/DESIGN.md#7-the-runtime-deferred-tiered-variant) answers the Loom
question in depth — the short version: this *is* userland Loom, and earns its keep where
Loom's thread semantics don't fit, the sharpest case being fork/join dataflow that a graph
scheduler needs to see rather than block on.

## Status

**Working:** suspension with non-empty operand stacks (`1 + (2 * await(f)) + mul(await(g), 3)`
— the case the tree-level ANF transform exists to forbid); loops; try/catch (failed futures
reach the user's handler); two-slot primitives; `new Foo(await(f))` via Kotlin-style
NEW-sinking (nested/conditional/statement shapes); instance methods (`this` is just entry
local 0; the state machine joins the host's nest for private access); liveness-driven nulling
of dead ref slots (a suspended frame pins only what the resumed code can still read — the
analogue of scala-async's `fieldsToNullOut`); per-state `$asyncDebug`
frame metadata; `AsyncDebug.describe`; LVT/line-number carry-over; the agent, the
`async`/`lift` APIs, and the no-agent runtime fallback, each with JDI-verified debugging;
**transitive elevation** — a method that blocks only because it calls a suspendable sibling
(no `await` of its own) gets a suspending `$async` variant too, the agent rewriting
`invoke g` to `await(g$async(...))` for every suspendable same-class callee (the static
in-class slice of the "elevate the blocking tier" design, docs/DESIGN.md §7.7).
60 tests, including a semantic matrix running every sample blocking vs. transformed, fast
path vs. real suspension.

**Rejected with diagnostics** (rather than miscompiled): await under a monitor, await in
constructors.

## Layout

| | |
|---|---|
| [`runtime/AsyncRT`](src/main/java/async3/runtime/AsyncRT.java) | the `await` marker; default implementation blocks (tier 0) |
| [`runtime/Async`](src/main/java/async3/runtime/Async.java) | lambda front end (cracking + `defineHiddenClass(NESTMATE)`), `lift`, shadow mode |
| [`runtime/FutureStateMachine`](src/main/java/async3/runtime/FutureStateMachine.java) | state machine base; ABI mirrors `scala.tools.testkit.async.AsyncStateMachine`; the `refs`/`prims` frame |
| [`transform/AsyncTransformer`](src/main/java/async3/transform/AsyncTransformer.java) | the ASM transform: class-per-method shape and the agent's in-place sibling shape |
| [`agent/AsyncAgent`](src/main/java/async3/agent/AsyncAgent.java) | load-time agent (`Premain-Class` in the jar manifest) |
| [`samples/`](src/main/java/async3/samples) | source-shape inputs; [`HandWrittenSumTwice`](src/main/java/async3/samples/HandWrittenSumTwice.java) is the expected output, by hand (phase 0) |
| [`demo/Demo`](src/main/java/async3/demo/Demo.java) | the runnable walkthrough above |
| [`src/test/java`](src/test/java) | semantic matrix, rejection tests, JDI breakpoint checks (`JdiAgentCheck`, `JdiShadowCheck`) |

## Future work

- **Spill-store elision** — dead ref slots are already nulled rather than spilled; the
  remaining (purely throughput) refinement is skipping the stores and restores for dead prim
  slots too. Measure with the JMH work below before bothering.
- **The lazy tier switch** — `invokedynamic`/`MutableCallSite` dispatch that starts on the
  blocking tier and flips to the transformed version when profiling says hot-and-blocking
  (design doc [§7](docs/DESIGN.md#7-the-runtime-deferred-tiered-variant)); the agent already
  materializes the method pair this needs.
- **JMH numbers** — blocking vs. AoT-transformed vs. lazily flipped vs. the current compiler
  phase's output; generic two-array frame vs. Kotlin-style typed fields.
- **IDE debugger support** — the remaining stepping gap (step-over at a real suspension steps
  out) is the same one Kotlin closes with dedicated tooling, which is the model to follow:
  the IntelliJ Kotlin plugin's
  [coroutine debugger](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin/jvm-debugger/coroutines)
  (a `PositionManager` plus an `AsyncStackTraceProvider` that render suspended coroutine
  frames alongside real ones), backed by
  [kotlinx-coroutines-debug](https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-debug)'s
  `DebugProbes` agent for dumping live coroutines. async3's `$asyncDebug` metadata and
  `AsyncDebug.describe` are the seed of the same capability: a small debugger extension could
  auto-plant resume breakpoints for logical step-over and render suspended state machines as
  async stack frames.

Two applications (Java, Akka) that exploit what this transform does and Loom does not —
suspend against a *custom scheduler* with a *serializable frame* rather than parking a thread:

- **Durable workflows (Akka SDK).** An Akka SDK `Workflow` is a hand-written state machine
  today — named steps, typed transitions, an explicit state class. The spilled frame
  (`refs`/`prims` + `state`) *is* a durable snapshot: write the workflow as straight-line
  `await` code, persist the frame at each suspension, resume across restart/rebalance
  (Temporal/DBOS-style durable execution). A parked virtual thread can't be serialized, so
  Loom doesn't reach this.
- **`await` inside a typed actor (Akka).** The ask-within-actor dance — a response-wrapper
  message, a `become`/stash continuation, a second handler — is exactly the continuation this
  transform generates. With the mailbox+dispatcher as the scheduler, `await(ask(...))`
  suspends the *actor*, not a dispatcher thread, buffering messages per a declared policy and
  resuming in-context with ordering intact, where blocking a virtual thread would block the
  actor's single logical thread. The bytecode transform is identical to the workflow case; an
  annotation only selects which scheduler the suspension targets (journal vs. mailbox).

See the design doc's [phase list](docs/DESIGN.md#8-prototype-plan) for the full plan.
