# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side; a pointer into another repository is the
fragile kind this log exists to avoid.

Links below point into this repository's [`README.md`](./README.md), which carries the detail an
entry deliberately leaves out.

### 1. The workflow aggregate is shared as process variables

Camunda 7 runs embedded, so the EL resolver could read the aggregate live - and that is
exactly what makes a model portable in one direction only: `${riskAcceptable}` would work
here and fail on every remote BPMS. The values an aggregate shares are therefore written as
process variables at every point this adapter talks to the engine, and the engine evaluates
its expressions against them. Reading an attribute through the EL resolver survives as a
migration fallback for workflows started before, reported once per name and removed in 2.1.
See [Sharing the workflow aggregate](./README.md#sharing-the-workflow-aggregate).

### 2. A workflow is progressed after the caller's commit

Every operation which moves a process forward is scheduled through the phase-two outbox,
the way a remote BPMS works. Sharing the caller's transaction was possible and is not
enough: an
engine command which loses a concurrency conflict cannot be repeated inside that
transaction, because it leaves the transaction rollback-only, and repeating just the engine
part would advance the process while the application rolls back.

What phase one still does is ask - an embedded engine answers for free and in the same
transaction, so a gone task or an unknown workflow is reported where the application called.
What phase two does is idempotent, because the outbox dispatches at-least-once. A test which
called VanillaBP has to wait for the engine to catch up rather than read its state in the
next line.

### 3. Workflow modules are kept apart by scoping the identifiers

Camunda 7 has tenants, but a workflow module may also prefix its identifiers instead, and
then there is no tenant to ask. The engine is therefore always addressed with the SCOPED
identifiers - process ids, message and signal names, error codes and task definitions - while
the core's registries stay keyed by the plain ones, and a delivery coming back from the
engine is translated before the core sees it. The mode is configured per workflow module,
which is why no code may assume either shape.
See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 4. A class opens its fields one by one, not as a whole

The process service, the deployment service and the engine holders of this adapter hold dozens
of fields, most of them collaborators nobody outside the class needs. Which of them a caller
may read belongs to the surface of the class, so an accessor is declared per field, and
`@Getter` on the class is refused even where an IDE offers it: it would publish the current
field list and then keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class is what keeps that offer from
coming back.

### 5. The adapter changes the BPMN it deploys, and only in ways the model's author can predict

An embedded engine offers no other seam. What a remote BPMS gets for free from its own protocol
this adapter has to put into the model before it is deployed, so `prepareBpmn` and `wireBpmn`
add: the `asyncBefore`/`asyncAfter` flags which make a service-like task a transaction boundary,
built-in task listeners for the user-task events, execution listeners for the workflow starts the
engine initiates and for the end of a workflow, the business key handed into a call activity which
runs on the SAME workflow aggregate, and the scoped identifiers of decision 3.

Each of those is bounded by a rule which keeps the deployed model predictable. A listener is
added only where a handler exists, the business key is not injected where the called process has
an aggregate of its own or where the application modelled a `camunda:in businessKey` itself, and
the scoping rewrite runs once per FILE rather than once per process, because all processes of one
file share a model. What the adapter adds is listed in
[What the adapter changes in the BPMN it deploys](https://github.com/vanillabp/camunda7-adapter/wiki/Home#what-the-adapter-changes-in-the-bpmn-it-deploys)
in the wiki.

### 6. A task handler runs inside the engine's own job transaction

Camunda 7 delivers a task inside the transaction of the job it is executing, so this adapter runs
the handler there rather than opening one of its own. That is what makes the three outcomes exact:
a `TaskException` throws a `BpmnError` with the aggregate COMMITTED, a handler which keeps the task
open leaves the activity open, and any other exception rolls the job transaction back and lets the
engine decrement its retries and deliver again.

The consequence is that the engine's retry IS the recovery here, which is why this adapter
contributes nothing to the delivery log of the platform: a redelivery proves that nothing was
committed. An application which gives the engine a datasource of its own loses that proof, and
that limit is documented rather than papered over.

That limit is now answered rather than only named, and the answer follows the datasource mode. An
engine on a datasource of its own runs its job
transaction on a resource the application's persistence cannot join, so VanillaBP opens the
transaction the handler and the workflow aggregate run in, that one commits before the job does, and
a job the engine hands out afterwards is a repeated delivery of committed work. Such an adapter id
therefore answers `deliversTasksAtLeastOnce()` with `true` and names each delivery by the id of the
job at hand, which the engine keeps across its retries; on the application's datasource nothing of
that happens, because there is nothing a record could add. A user-task notification stays unnamed in
both modes: one transaction creates every user task the token reaches, so the job would name several
notifications, and what is unique per task is generated while the task is created and does not
survive the rollback which produces the repetition.

### 7. `table-prefix` says the tables are already there

Camunda's own schema management ignores the prefix and creates unprefixed `ACT_*` tables. That is
not a guess, the engine says so itself in `ProcessEngineConfigurationImpl#setDatabaseTablePrefix`,
and its shipped DDL names every table literally. So `table-prefix` in this adapter means that
somebody else created the prefixed tables, and two guiding failures keep the misunderstanding out:
a prefix together with a schema-creating `database-schema-update`, and a prefix without the
tables. Both are checked before the engine is built, which also keeps stray unprefixed tables out
of a shared database.

Rewriting the shipped DDL in the adapter was tried and rejected: every substitution which looks
right hits the `ACT_ID_` and `ACT_INST_ID_` COLUMNS or leaves an index name behind, and neither
shows up before something runs. See
[Two engines on one database](./README.md#two-engines-on-one-database-table-prefix).

### 8. A probe and a write answer for the scope of the call, never for a business key alone

Aggregate ids are unique per aggregate type, not across an application, so two workflow modules
whose aggregates count from one both hold an id `1`. Every probe therefore filters
server-side by the scoped process definitions of the calling `WorkflowScope` and by the tenant,
for running as well as for historic instances, and the same filter narrows the instance lookup
behind `aggregateChanged`.

The write matters more than the answer. Setting a variable in Camunda 7 makes conditional events
re-evaluate, and the technical marker is written even where the aggregate shares nothing, so an
unfiltered lookup would not merely misreport a foreign workflow, it would advance it. Two
instances inside the adapter's own scope are a broken assumption and end with a message naming
the aggregate, the scope and both instance ids. The branch which was given a task id keeps no
filter, because an execution id names exactly one execution.

### 9. A nested shared value travels in the engine's own serialization format

Writing shared values as a JSON string was the first choice and was dropped: a dot-notated
expression has to navigate the value, which it can only do when the engine holds it as an object
variable. So `serialization-format` is resolved per workflow, workflow module and adapter, and the
adapter additionally honours the engine's `defaultSerializationFormat`.

That needs a data format in an embedded engine, which needs a process engine plugin, which this
adapter refused to accept before. Every `ProcessEnginePlugin` bean of the application is applied
now, and `engine-plugins` configures one by class name with its properties handed to Camunda's own
`PropertyHelper`, so the values convert exactly as they would in a `bpm-platform.xml`. Without a
format the adapter warns once that Java serialization applies.

### 10. A start asks the engine for numbers, and asks as many of them on the last day as on the first

The questions this adapter answers while an application boots read from tables which grow for as
long as it is in production: how many workflows still run on an old version of a process, how many
of them the configured scope will never reach, which versions the engine holds and what their
models look like. A start of ten seconds must not become a start of two minutes because the
application did its job for two years, and the platform states the rule for every adapter as
decision 19 of its own DECISIONS.md.

For Camunda 7 that means two things. A question about a quantity is a `count()` and the engine
answers it from an index; fetching the executions and counting the list is the same answer at a
price which rises every year. And a definition query is asked once for the whole process:
`fetchDeployedVersions` reads every version anyway, so it keeps the definition ids it saw, and the
questions which follow are answered from them rather than each asking again.

What does grow is the number of versions the engine holds, one per deployment which changed a
model, and the questions about older versions grow with it. That is deliberate: those questions are
what the check is for, and `outfaded-versions` is how an operator says which of them have stopped
being interesting. `Camunda7StartupQuestionCostTest` counts what a start asks.

### 11. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI used to ask for two methods per outbound operation, and this
adapter had eighteen of them: nine phase-one checks and nine phase-two actions, most of
them a single line forwarding to a private helper. Adding an operation meant adding two
more, in every adapter, next to the four places the core needed for the same operation.

The SPI now asks for a map instead: one `PhaseOperationHandler` per `PhaseOperation`,
each of them the pair of "ask" and "act" for this engine, and everything else about an
operation belongs to the operation. This adapter answers that map, and the eighteen
methods are gone. What the handlers do is unchanged - the helpers they call are the ones
the methods called - so nothing about the engine, the checks or the idempotency moved
with them.

Two things are worth knowing for whoever adds the next operation here. The map is the
statement about what this adapter serves: an operation missing from it is an operation
this engine has nothing like, and VanillaBP refuses the boot for the ones every adapter
has to serve. And the phase-one half is where this adapter earns its keep, because an
embedded engine answers from the caller's own transaction - see the platform's decision
29 for why the operation itself carries no engine knowledge at all.

### 12. A suspended process definition counts, and the only way past it is a system property

Deleting a process definition really removes it here: the engine drops it from the database and the
definition query stops answering with it, so the startup check for old process versions stops
reporting about it by itself. Suspending is the other thing Camunda 7 offers, and it is not the
same. A suspended definition comes back the moment somebody resumes it, its workflows never went
away, and a `@WorkflowTask` method missing for one of their tasks is still missing afterwards. A
check which went quiet because somebody suspended a definition would have dropped the finding
without anything being settled, and the finding would come back as an incident on a live workflow -
which is what the check exists to prevent. So the definition query names no suspension state, and a
suspended version is checked like every other one.

There is one situation that rule cannot cover: an application has to run now, and nobody is in a
position to clean up an old version at this minute. For it there is
`vanillabp.ignore-suspended-process-definitions`, next to it the environment variable
`VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS`, and the property wins where both are set. Only
the value `true` counts, in any case; anything else is reported and changes nothing.

It is a system property and not a configuration key, and that is the part most likely to be
"fixed" later by somebody who does not know why. A configuration key lands in an
`application.yaml`, gets committed, and is then set forever without anybody noticing it again.
This switch is meant to be the decision of exactly this start, typed where it can be seen, which is
also why every start says out loud that it was taken and which versions it hid, and why nothing
about having said so is remembered.

The environment variable is an equal way in rather than a convenience. A Quarkus native image reads
neither `JAVA_OPTS` nor `JAVA_TOOL_OPTIONS`, which is where a container usually carries its `-D`
arguments, so without the variable the switch would do nothing at all in a native image and say
nothing about it either. An emergency exit that quietly fails is worse than none.

The name carries no adapter part and no adapter id, because it holds for every VanillaBP adapter
whose BPMS can suspend a definition - today only this one - and somebody searching for a way out in
an emergency should find one switch instead of three. An application running two Camunda 7 adapter
ids cannot take the exit for one of them alone. That is the price, and it is paid on purpose.
`SuspendedProcessDefinitions` is the single place all of this lives.

### 13. The workflows of a renamed process are served from the models the engine still holds

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Camunda 7 evaluates the expressions of the model a workflow was
STARTED with, so those workflows need the connectables of a model this application no longer has:
the expression text of every task, the element it is evaluated at, and the way the task is wired.
The engine has that model in its own repository, so the adapter reads it from there, version by
version, through the same extraction a deployed model goes through.

The alternative was to build the connectables from what the application says it serves. The core
names that (`taskWiringOfProcessesNobodyDeployed`), and it is enough on a BPMS where a
subscription is a name. Here it is not, because a connectable is more than a name. A
`camunda:expression` task completes when its handler returns while a `camunda:delegateExpression`
task can stay open for a `@TaskId` method, the difference decides what the EL resolver hands the
engine back, and no list of task definitions says which of the two a task is. Guessing it would
either close a task which has to stay open or keep one open which nobody will complete.

Two things follow from reading the models. Every version the engine holds is wired, because a
workflow may sit on any of them, and a task which several versions share is registered once. And
the wiring validation is NOT run over them: they were deployed by an earlier generation of this
application, a task it dropped in the meantime is what the check for old process versions reports
with the count of the workflows affected, and ending a boot over a model nobody can change any
more would answer that finding with the wrong instrument.

A model the extraction refuses is skipped with a warning rather than allowed to end the boot. The
same extraction refuses an external task and an expression VanillaBP does not understand, and a
model THIS BOOT brings should be refused that way - it can still be fixed. A model deployed years
ago cannot, so the versions which can be wired are wired and the one which cannot is named
together with what a workflow on it walks into.

The definition query this needs is the one the version check runs anyway. `fetchDeployedVersions`
reads every version of the process and now keeps the definition ids by version, so reading the
models costs no query of its own, which is what decision 10 asks for.
`Camunda7DeclaredProcessWiringTest` holds the task kinds and the skipped version,
`Camunda7RenamedProcessIT` the whole thing against a running engine.

### 14. An extension reaches the engine through a customizer, not through the engine

The Business Cockpit of version 1 got its engine hooks from Camunda's Spring Boot starter: an
engine plugin carried its parse listener, and the starter's history eventing told it that a
workflow had started or ended. This adapter builds the plain engine itself - both starters are
version-locked to platform releases which have reached their end - so neither exists any more,
and an extension had nowhere at all to put a listener.

`Camunda7EngineCustomizer` is that place, and it is C7-specific SPI living in this repository
rather than in the VanillaBP core: engine hooks belong to the engine's adapter, and a core which
knew about BPMN parse listeners would be knowing about Camunda 7.

Three things it can contribute, and each shape says something. Parse listeners go BEFORE or AFTER
VanillaBP's own, because that is what decides the order of the listeners they attach to an
element - a built-in task listener contributed "after" runs after the one the adapter attached,
which is the order an extension tracking user tasks needs, and it is a property of Camunda's own
pre/post parse-listener lists rather than of anything this adapter arranges
(`Camunda7EngineCustomizerIT#theParseListenerSeesTheUserTasksAfterVanillaBp` is the pin). A
history event handler is installed as a COMPOSITE next to the engine's own, so the history level
and everything else about history stays as configured and history is still written. And
`customize` hands over the configuration itself, for what these two do not cover.

A customizer is asked once per configured adapter id, with that id. Two ids are two engines, and
an extension registering something per engine has to be able to tell them apart - the same reason
every other per-adapter object of this adapter exists once per id.

### 15. A check reads the engine's models without asking who deployed them

The platform's decision 38 carries the rule: a check against a BPMN model must not depend on
which application version deployed the model it judges, and a check which cannot see every model
that could carry the answer stays silent instead of refusing. On this adapter the rule lands in
three places.

The engine STARTS workflows of a declared-only id on its own. The timer of the old model's
latest version keeps firing after the rename, and its signal subscription keeps matching, so
every listener serving such a start has to find its way back from the engine's definition key to
the workflow module and the plain id. That registration happens the moment the core asks for the
id's version catalog (`processVersionCatalogOf`), not later: the first read of that catalog makes
the engine parse the old definitions, the parse listener decides by exactly this registration
whether an end listener is attached, and a parsed definition stays cached - registering after
the parse loses the end notification for good. The plain signal names of a held model's signal
start events are read from the engine's own copy while its versions are wired, because nobody
registered them at deployment: nothing was deployed.

The refusals of the extraction judge a model being DEPLOYED. A model the engine already holds is
only being read - by the wiring of a declared id (decision 13 skips such a version with one
warning) or by the startup check about older versions, which used to END THE BOOT over an
external task (`camunda:topic`) in a version deployed years ago. Both read paths warn now,
naming the version as a version instead of rendering it into a message slot which said "file",
and the check answers for the rest of the model.

`Camunda7DeclaredIdRuntimeIT` measures the starts and the end against a running engine under
`use-prefix`, `Camunda7HeldModelReadingTest` the reading which never refuses.

### 16. A number the engine has no variable type for keeps its class

Camunda 7 stores a short, an integer, a long, a double, a boolean, a string, a date and bytes as
themselves. A `BigDecimal`, a `BigInteger` and a `Float` fit none of those, and this adapter used
to widen them to a double on the way in. So the value a model read was not the value the
application held: `120.50` renders as `120.5`, a `BigInteger` above 2^53 loses its last digits, a
`Float` of `0.1` renders as `0.10000000149011612`. Version 1 read the live workflow aggregate and
had none of that.

The conversion bought nothing either. EL coerces both operands of a relational operator to
`BigDecimal` as soon as one of them is one, so `${total > 100}` compares numbers against the
object itself. Equality is the one it does not rescue: `${total == 120.50}` coerces the same way
and then calls `equals`, so it is false against the live object and was true against the widened
double. Version 1 answered false as well, because it held the live object too, and a model
comparing a decimal for equality was already wrong before the upgrade.

So those three types travel as object variables, like every other value the engine has no type
for, and in the format decision 9 names. The `Character` conversion stays: EL compares a character
to `'A'` and to `"A"` alike, so writing it as a string loses nothing and buys a readable variable.

The price is that the format then decides what the value reads as, and this adapter reports that
instead of working around it. A startup check writes a sample of the type through the engine's own
serializer for the configured format and warns where what comes back is not what went in, per
attribute the models read. It measures rather than judging from a list of types, because the
dataformat belongs to the application and a warning about something which works is worse than a
missing one. Where the adapter's own configuration names no format it says nothing at all, and it
says nothing where it cannot measure: no sample of the type, no serializer for the format, or an
engine which will refuse the first push loudly by itself.

`Camunda7VariablesTest` holds the rule and `AbstractNestedExpressionsIT` with its two subclasses
holds what each format answers at runtime. The check is held by
`Camunda7SerializationRoundTripTest` and `Camunda7LossyFormatCheckIT`.

### 17. The engine answers for a process id and a decision id, and the deployment stamp is only a hint

The platform's decision 38 carries the rule: a check which cannot see everything that could
carry its answer stays silent, and where the BPMS can be asked, a finding about a model
somebody else deployed is a warning and never the end of a boot. Its decision 40 divides the
work for a name clash, the adapter asking and the core wording the message. What follows for
Camunda 7 is what this engine can be asked and how far its answer carries.

Two identifier kinds are keys of the repository, so both are queryable. Every BPMN process of a
workflow module is asked about in one statement (`processDefinitionKeyIn`), and a decision is
asked about one by one because its query has no batch filter. Neither query names a version:
our own deployment creates the newest version of a key another deployment may have held for
years, so asking for the latest version would find nobody but ourselves. Neither names a
suspension state either, for the reason decision 12 gives. The tenant belongs in both filters,
because a second tenant on one engine is a legitimate arrangement rather than a clash.

A message name, a signal name, an error code and an escalation code are in no index. They live
in the models, and reading one model per version the engine holds is the growth decision 10
forbids a start to have. Where the models are read anyway the question is free, and that is
where it is asked: from what was read out of the models of this deployment, and from the model
of a held version the check of older versions already reads. A task definition is not a
question here at all, because Camunda 7 keeps one process-local: the expression is evaluated
inside the process by VanillaBP's EL resolver and nothing subscribes to it engine-wide, which
is why this adapter does not rewrite one either.

The hard part is not the query, it is telling a foreign holder from this application's own
history. The Camunda deployment is what can be asked about, and the part of its stamp which
carries is the NAME: every VanillaBP generation deploys a workflow module under the module's
own id, version 1 included, which wrote the application name into the source where this adapter
writes the adapter type and the adapter id. So the deployments named after the module being
deployed are one query, and a definition belonging to one of them says nothing. Reading the
source instead would report every definition of an application upgrading from version 1, on
its first start and on every one after it.

A definition under another deployment name is reported, and the source then says how sure the
adapter can be. A source naming a Camunda 7 adapter of VanillaBP is reported without certainty,
because another adapter id is what a migration between two engines looks like and another
workflow module of this application writes its own name as well. Any other source was written
by something else, a modelling tool or an application which deploys its models itself, and that
one is reported as certainly foreign.

What this cannot see is a second application which deploys a workflow module of the same id. It
writes the same deployment name, and no column of the engine's deployment table names an
application. Reading that name as ours is what keeps the check from warning about this
application's own history on every boot, which is the price, and it is paid on purpose. Nothing
of all this is cached and no runtime path reads it.

`Camunda7IdentifiersTheEngineHoldsTest` holds the queries and the stamp against a real engine,
`Camunda7DeclaredIdentifiersTest` what a model declares and what a held version still
declares, and `Camunda7StartupQuestionCostTest` that a boot asks once per workflow module plus
once per decision.

### 18. An idle engine is told when to wake up instead of asking whether it is time yet

The Camunda 7 job executor does not wait, it polls: every 5 seconds, widening to 60 while nothing
happens, and every one of those cycles is a database command. An application which spends most of
its life waiting for a timer pays for that on a database billed by active use. The answer is not a
longer interval, because a longer interval makes a timer late without making the polling stop. The
answer is a question with an exact answer: when is the next job due.

So a cycle which found nothing asks the engine for the one job with the earliest due date later
than the moment that cycle began, and waits until then. Found nothing has to mean all four of
these at once, or the wait would be about load rather than about a due date: every engine handed
out fewer jobs than were asked for, nothing was lost to another node's lock, the acquisition did
not fail, and the threads executing jobs were not full. Anything else keeps the engine's own
backoff, untouched.

Two details of that question are decisions rather than taste. It asks for ONE row, because the
answer needed is a moment and every further row is read by the database and looked at by nobody;
version 1 listed every future job and used the first. And it uses the clock of the cycle which
just ran, not a fresh one. Reading the clock again opens a gap of a few milliseconds in which a
job falls due, appears in neither answer and is never woken for; the acquisition loop subtracts the
same moment from the wait it gets back, so a job which came due meanwhile starts the next cycle at
once. Version 1 also let the ordinary backoff reach its 60 second ceiling before the due-date logic
engaged at all, which spent about two minutes of polling on every quiet period to save one query
per cycle. The query is one indexed row and it replaces the cycle it would otherwise have paid for,
so the staging is gone.

There is no cap on how long this may wait. The due-date query answers the question exactly, and a
job this node writes itself wakes it. The platform's outbox answers the same question with a cap,
because there a second node's write cannot be seen at all; here the cap would only paper over the
case named below, and a reader who thinks it is about timing sets it to seconds and gives the whole
saving back.

The waking hangs off the TRANSACTION, not off a list of methods. Every engine command asks its own
commit to wake the acquisition, and a job can only be written by an engine command, so a workflow
which is started, a message which is correlated, a task which is completed, a signal, a
continuation the engine wrote for itself, a retry it rescheduled and a request to Cockpit or to the
REST API are all covered without any of them being named in the code. Version 1 published an event
from four places in one class, which left a plain save of a workflow aggregate, a signal and an
engine-internal continuation waking nothing.

That hook is the engine's command chain rather than a post-commit hook per platform, and the reason
is that neither platform has one. Spring's and Quarkus' post-commit mechanisms have to be called
from inside the transaction by somebody, and the only place this adapter is inside every
transaction which could have written a job is the engine's own command. Underneath, the engine's
transaction context uses exactly those two mechanisms: on Spring Boot a synchronization registered
with `TransactionSynchronizationManager` acting on `afterCommit`, on Quarkus a JTA synchronization
acting on a completion which committed. What the adapter adds is the one place which registers it.

What the engine already does by itself is worth saying, because it is what makes the due-date wait
the feature and the waking a detail. `JobManager` hints its own executor after a commit which
inserted a job due now, and for a timer it hints only where the due date falls inside the
executor's wait time. A job due LATER is the gap, and without the due-date wait, covering that gap
shortens nothing at all.

What this does not cover is a second application writing jobs into the same engine. That
application's commit runs in its own process and reaches no listener here, so a node waiting on a
due date computed before that write learns about the job when its own question next runs. Two
applications against one engine is not a setup this adapter supports, and this is one of the
reasons.

The engine's metrics reporter is switched off where the feature is on. It owns a timer of its own
and writes its counters through a database command every 900 seconds, which would wake an engine
four times an hour that was meant to stay quiet, and a feature which waits for an hour and is woken
by its own metrics has saved nothing. Only the writing stops; the counters are still kept in
memory. An application which needs the written metrics says so with `db-metrics-reporting`, and the
startup message tells it what that costs.

Jobs are acquired by due date where the feature is on, because waking for the earliest due job and
then acquiring in an unrelated order picks the wrong job as soon as more are due than one cycle
takes. That order wants a database index, which is the operator's work and which the startup
message asks for by name. `jobExecutorPreferTimerJobs` is left as the engine has it: a preference
between kinds of job is a different question, and version 1's README claimed both flags while its
code set neither.

`Camunda7DueDateSleepTest` holds the waiting rule against a real engine, including the count of
connections an idle engine takes, a job due later, a commit which shortens a running wait and an
engine holding no job at all. `Camunda7JobExecutorSleepTest` holds what the two keys decide and
what the startup message says, `Camunda7StartupQuestionCostTest` that the question asks for one
row, and `Camunda7SleepingEngineIT` with its Quarkus twin that a booted application on either
platform takes no connection while nothing is due.
