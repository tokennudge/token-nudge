# TokenNudge — inverted WireMock for Camunda 7 and CIB Seven

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tokennudge/tokennudge-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.tokennudge/tokennudge-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

TokenNudge reaches into a running Camunda 7 / CIB Seven engine and nudges stalled process
instances past their wait states — completing external tasks, correlating messages,
completing user tasks — according to declarative, WireMock-style rules. It solves the
black-box integration testing problem: when your service embeds a BPMN process, something
external has to play the workers, message producers, and users that would advance the process
in production, and hand-rolled polling loops for that get old fast.

## Quickstart (< 5 minutes)

Add the test-scoped dependencies:

```xml
<dependency>
  <groupId>io.github.tokennudge</groupId>
  <artifactId>tokennudge-camunda7</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.github.tokennudge</groupId>
  <artifactId>tokennudge-junit5</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Register the extension, simulate a wait state, drive your service, verify (modelled on
[`tokennudge-examples`](tokennudge-examples)):

```java
import io.github.tokennudge.junit5.TokenNudgeExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;

class PaymentFlowIT {

    @RegisterExtension
    static final TokenNudgeExtension nudge =
            TokenNudgeExtension.forEngine("http://localhost:8080/engine-rest");

    @Test
    void chargesTheCardAndCompletesThePayment() {
        nudge.simulate(externalTask("charge-card")
                .inProcess("payment")
                .willComplete(withVariables(Map.of("authorized", true))));

        // ... call your service's public API, which starts/advances the "payment" process ...

        nudge.verify(externalTask("charge-card").completed().times(1));
    }
}
```

That's it: no white-box access to the engine, no hand-rolled polling — TokenNudge discovers
the waiting external task, matches it against the rule, completes it, and lets you verify it
happened.

## API surface, in brief

- **External tasks:** `externalTask(topic)`, then `.willComplete([variables])`,
  `.willFailWithBpmnError(errorCode[, message, variables])`, `.willFail(message[, retries,
  retryTimeout])`; verify with `.completed()`, `.failedWithBpmnError([errorCode])`, `.failed()`.
- **User tasks:** `userTask(taskDefinitionKey).willComplete([variables])`; verify with
  `.completed()`.
- **Message correlation, both strategies:** `message(name).willCorrelate()` (by process
  instance) or `.willCorrelateBy(businessKey()[, variables])`/`.willCorrelateBy(processInstance(),
  variables)`; verify with `.correlated()`.
- **Matchers**, common to every wait state kind: `.inProcess(processDefinitionKey)`,
  `.atActivity(activityId)`, `.withBusinessKey(key)`, `.withVariable(name, value)`,
  `.withVariableMatching(name, predicate)`.
- **Verification API:** every terminal above returns a `Verification`; refine with
  `.times(n)`, `.atLeast(n)`, `.atMost(n)`, `.never()`, `.within(timeout)`,
  `.withVariable(name, value)`, or use the kind-agnostic `.reached()`/`.handled()` from the
  matcher itself. Pass the result to `nudge.verify(...)`.
- **`reset()`** clears registered simulations, the journal, and internal "already handled"
  memory (never touches engine state); `resetJournal()` clears only the journal.
- **JUnit 5 lifecycle:** `TokenNudgeExtension.forEngine(url)` (or a `Supplier<String>` for a
  dynamically assigned port), `.pollInterval(...)`, `.verifyTimeout(...)`,
  `.failOnActionErrors(boolean)` (default `true`), `.failOnUnmatched(boolean)` (default
  `false`), `.configure(builder -> ...)` for anything else `TokenNudge.Builder` supports.
- **Without JUnit 5:** `TokenNudge.forEngine(url).pollInterval(...).start()`, then the same
  `simulate`/`verify`/`reset` calls directly; `TokenNudge.forAdapter(...)` for a custom
  `EngineAdapter`.

## Compatibility

- **Engines:** Camunda 7.24 (final CE release) and CIB Seven 2.2 (the maintained CE fork),
  both over `engine-rest` — no engine-specific behaviour was needed for either.
- **Java:** 25.
- **Deployment:** remote engines only, reached over HTTP via `engine-rest`. No embedded-engine
  mode (see Limitations).

## How it works

One background loop, polling on an interval: **poll** the engine for wait states matching any
registered rule's kind and name, **match** each discovered wait state against the registered
rules in precedence order (most specific/most recently registered wins), **claim** it so a
concurrent worker can't act on it too, **act** (complete, correlate, fail, throw a BPMN error)
according to the matched rule, then **journal** the outcome — handled, unmatched, claim lost to
a race, or a definite action failure — for `verify(...)` to inspect later.

## Limitations and gotchas

- **`reached()`/`never()` only mean something for a wait state some rule covers.** TokenNudge
  only polls topics/task keys/message names referenced by at least one registered simulation;
  asserting `.reached().never()` for a name nothing is registered for passes vacuously, no
  matter what the process actually did.
- **A static `TokenNudgeExtension` field is not safe under JUnit 5 parallel test execution.**
  One test's `afterEach` reset can race with another, concurrently running test's
  `simulate`/`verify` calls on the same shared instance. Use `@Execution(SAME_THREAD)` or a
  class-keyed `@ResourceLock` for any class using a shared static field.
- **Known engine races:** a wait state completed by another worker, a human, or the process
  itself between discovery and this worker's own attempt to act on it is reported as a benign
  `CLAIM_LOST`, never an action error — for external tasks (an engine-side lock protects
  against this) and, since the final round of v1, also for user tasks and message correlation
  (which have no lock endpoint on `engine-rest`, so the race is instead detected from the
  engine's own rejection of the request). Two process instances sharing a business key both
  correlating the same message end up as a definite action error, carrying the engine's own
  ambiguous-correlation message — TokenNudge can't guess which one you meant.
- **Variable snapshot scope** is process-scope plus the waiting wait state's own
  execution-local scope only; values scoped to an intermediate nested subprocess or
  multi-instance execution in between may be missed.
- **Out of scope for v1:** embedded-engine mode, Camunda 8 / Zeebe, timers, DMN, and any UI.

## Contributing / discoverability

`0.1.0` is published on Maven Central, so the dependencies above are all you need. To work on
TokenNudge itself, build from source with `./mvnw -B clean verify` (see below). The release
procedure is in [RELEASING.md](RELEASING.md).

Suggested GitHub repository topics, for discoverability: `camunda`, `camunda7`, `cibseven`,
`bpmn`, `external-task`, `integration-testing`, `wiremock`, `testcontainers`.

## Building

```
./mvnw -B clean verify                                        # all modules, both unit and integration tests
./mvnw -B verify -pl tokennudge-camunda7 -am -Dtokennudge.it.engine=cibseven   # against CIB Seven instead of Camunda 7
./mvnw -B -Prelease verify                                     # + Javadoc/sources jars, doclint-checked
```

Integration tests use Testcontainers and need a working local Docker daemon.
