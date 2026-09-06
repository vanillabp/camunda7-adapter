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
