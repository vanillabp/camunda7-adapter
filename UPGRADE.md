# Upgrade notes

Contributor-facing list of what a VanillaBP 1 application on Camunda 7 has to do, organised per
version line. It describes the step to the release, not how the release was built - the
development history is in git. These entries feed the user-facing
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1);
the same file exists for
[VanillaBP itself](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md)
and for the [Camunda 8 adapter](https://github.com/vanillabp/camunda8-adapter/blob/main/UPGRADE.md).

## 2.0

### A BPMN expression reading a nested value needs a serialization format

Version 1 answered the expressions of a model from the live workflow aggregate, so
`${order.customer.name}` navigated a Java object and there was nothing to configure. Version 2
writes what the aggregate shares as Camunda process variables, and a nested value - an object or a
collection - becomes an object variable, which the engine stores in whatever serialization format
it was told to use.

Name the format, and give the engine the dataformat which provides it:

```yaml
vanillabp:
  adapters:
    camunda7:
      serialization-format: application/xstream
      engine-plugins:
        xstream:
          plugin-class: org.camunda.xstream.ProcessEnginePlugin
```

`application/xstream` comes from [camunda-xstream](https://github.com/RasPelikan/camunda-xstream),
`application/json` from the SPIN JSON dataformat, and the dependency providing it belongs to the
application. VanillaBP passes the adapter-level value to the engine's `defaultSerializationFormat`
as well as to every variable it writes, and a workflow module or a single workflow may deviate
(`vanillabp.workflow-modules.<module>.adapters.<id>.serialization-format`,
`vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<id>.serialization-format`).

Configure nothing and the engine's own default applies, which without a dataformat is Java
serialization. Cockpit then shows a base64 blob where the data should be, so nobody operating the
workflows can read what a decision was made on. And the engine's database holds serialized
instances of the application's own classes, so `${order.customer.name}` keeps evaluating only as
long as the class in the database still matches the class on the classpath. The adapter warns
about it once per JVM when it writes such a value, and the warning names the property to set and
the dataformat to add.

An application whose aggregates share nothing but scalars meets none of this: a scalar becomes a
scalar variable, and `${amount > 1000}` compares numbers as it did before.

The [configuration page](https://github.com/vanillabp/camunda7-adapter/wiki/Configuration#nested-values-and-why-java-serialization-is-not-an-option)
of the wiki carries the narrated version, and why a nested value travels in the engine's own format
rather than as a JSON string is decision 9 in the repository's [`DECISIONS.md`](./DECISIONS.md).

### What the startup check tells you about your expressions, and what it cannot

Version 1 answered every BPMN expression from the live workflow aggregate, so nothing a model read
could be missing. Version 2 writes what the aggregate SHARES, so an expression reading something
unshared reads `null` instead, and on Camunda 7 a null is often silent: a gateway with a default
flow takes it, a conditional event answers false and keeps waiting without an incident and without
a log line, and a completion condition which is never true lets a multi-instance element end as if
it had done its work.

The adapter therefore lists such expressions while your application boots, one WARN each, naming
the element, the expression, the segment which stops the path, what this engine will do with the
null and how to fix it. Nothing fails: an expression the check misreads must not keep your
application from starting. Treat the list as your migration backlog and work it off before you go
live, because the runtime will not remind you.

Know where the list ends. The check reads the DECLARED types of an expression's path and stays
silent wherever they cannot decide, which is a `Map`, an interface, an abstract type or a
collection which does not say what its elements are. It judges no method call either, so
`${order.getTotal()}` and `${order.status.name()}` are not in the list although both stop working
on version 2: the flattened value is a map and a text, neither of which has those methods. Those
two shapes are LOUD at runtime, an incident naming the expression and the class it looked at, which
is why they are left to the engine. What is silent at runtime is what the check is for.

The list also does not cover the input expressions of a business rule task, and it says nothing
about Camunda 8 or the Process-Engine-API. Those adapters share the same flattening, so the same
expressions meet the same missing keys there, but neither hands the adapter a parsed model of this
shape and FEEL is not JUEL. The gap is known and not closed here.

The [configuration page](https://github.com/vanillabp/camunda7-adapter/wiki/Configuration#migrating-from-an-adapter-which-read-the-aggregate-live)
of the wiki carries the narrated version.

### A renamed BPMN process no longer needs its old model deployed

An application may declare a BPMN process id it deploys nothing under
(`@WorkflowService(secondaryBpmnProcesses = ...)`), which is how a renamed process keeps being
served. On version 1 that declaration reached the handler registry and not the engine: this
adapter wired the tasks of the models it deployed, so a workflow still running under the old id
reached its next task, found nothing wired to it and ended in an incident. The way through was to
keep the old BPMN file next to the new one until those workflows had ended.

Version 2 wires the old id as well. While a workflow module starts processing, the adapter asks
the core which ids it declares without a model and reads the model of every version the engine
still holds under each of them, out of the engine's own repository. Those workflows then reach the
`@WorkflowTask` methods of the current application, which is what the declaration always promised.

Nothing has to be configured for it and nothing has to be removed. An application which keeps the
old model deployed is unaffected, since then nothing is declared without a model. An application
which deleted the old file gains one line per declared id in the log of every start, saying how
many versions were wired, and it costs no query beyond the one the check for old process versions
runs anyway.

Two things are worth knowing while the declaration stands. The engine has to still hold the old
definitions, which it does as long as workflows run on them, and a version whose tasks the
application dropped is reported by the check for old process versions with the number of workflows
it affects, exactly as for the older versions of any process. The
[recipe for a rename](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
is on the platform's wiki.
