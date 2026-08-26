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

`needsTwoPhaseCommitForStartingWorkflows()` answers `true` for every adapter id, and every
operation which moves a process forward is scheduled through the phase-two outbox, the way
a remote BPMS works. Sharing the caller's transaction was possible and is not enough: an
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
