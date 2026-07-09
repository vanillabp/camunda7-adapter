# VanillaBP adapter for Camunda 7

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

> **Status: Version 2 skeleton.** This repository currently contains only the
> structural skeleton of the adapter (Maven modules, SPI implementation stubs, Spring
> Boot registration and a boot smoke test). No BPMS feature behaviour is implemented
> yet — BPMN deployment, starting workflows and task wiring arrive story by story. The
> SPI pipeline methods throw `UnsupportedOperationException` until then.

## Coordinates

The adapter is built on top of `adapter-platform-integration` (the platform-neutral
migration adapter plus the Spring Boot integration).

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

|    Module     |            Artifact            |                        Contents                         |
|---------------|--------------------------------|---------------------------------------------------------|
| `core`        | `camunda7-adapter`             | Platform-neutral SPI implementations + engine wiring.   |
| `spring-boot` | `camunda7-adapter-spring-boot` | Spring Boot auto-configuration registering the adapter. |

## Configuration

The adapter is a VanillaBP adapter of type `camunda7`. Configure an adapter instance by
giving it an id and pointing its `type` at `camunda7`:

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
  prioritized-adapters:
    - c7
```

The same type may be configured under several ids (e.g. two Camunda 7 engines side by
side during a migration).

## Supported Camunda version

Camunda **7.24** is the final feature release of Camunda 7 (October 2025, LTS). The
Camunda 7 community edition is **end-of-life** — no further community releases are
expected. This adapter pins Camunda `7.24.x`.

Camunda 7 runs **embedded** inside the application's JVM and shares the same database and
the same transaction as the business code. Consequently starting a workflow happens
completely within the local transaction (no two-phase commit / transaction outbox), and
engine queries are immediately consistent.

## No Quarkus module

This repository intentionally ships **only** `core` and `spring-boot` — there is no
Quarkus module. Camunda's own Quarkus extension is version-locked to older Quarkus
releases, and Camunda 7 is end-of-life, so investing in a Quarkus variant is not
worthwhile. VanillaBP applications on Quarkus should target a maintained BPMS adapter
(e.g. Camunda 8).

## Known issues

- **`camunda-bpm-spring-boot-starter:7.24.0` is incompatible with the Spring Boot 4.1
  baseline.** VanillaBP Version 2 builds on Spring Boot 4.1.0, whereas the Camunda 7.24
  Spring Boot starter targets Spring Boot **3.5.5** (`version.spring-boot` in
  `org.camunda.bpm:camunda-parent:7.24.0`). Its auto-configuration is compiled against
  Spring Boot 3.x APIs that moved or were removed in Spring Boot 4. Therefore the
  `spring-boot` module depends on `org.camunda.bpm:camunda-engine` directly and does
  **not** use the starter. Embedded-engine auto-wiring (data source, job executor,
  process-engine configuration) is deferred to a later story.
- **The core deployment pipeline calls `deployResources` even for modules without BPMN
  files.** `DeploymentService.deployResourcesOfAdapter` invokes the adapter's
  `deployResources` (and, on application-ready, `startWorkflowProcessing`) once per
  (workflow module × prioritized adapter), regardless of whether the module contains any
  BPMN file. With the current throwing skeleton stubs this means a full application boot
  (with `DeploymentAutoConfiguration` active) fails even when no BPMN is present. The
  boot smoke test therefore proves adapter discovery via `ApplicationContextRunner`
  without running the deployment lifecycle. This is a pre-existing property of
  `adapter-platform-integration`, not of this adapter, and disappears once the pipeline
  methods are implemented.

