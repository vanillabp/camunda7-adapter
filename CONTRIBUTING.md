# Contributing

This repository is the VanillaBP adapter for Camunda 7. It implements the adapter SPI of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration) and runs the
engine embedded in the application, in the application's own transaction. Business code never sees
it: what an application writes against is
[`spi-for-java`](https://github.com/vanillabp/spi-for-java), and everything this adapter does for
its users is described in the [wiki](https://github.com/vanillabp/camunda7-adapter/wiki).

Where the rules are: [`README.md`](./README.md) explains how the adapter works, module by module
and behaviour by behaviour, and it is the first thing to read.
[`AGENTS.md`](./AGENTS.md) says how work is done here, in the form an agent reads.
[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on, and it is the only
thing the code is allowed to cite.

## Building and testing

Java 21 and Maven, without a wrapper. Two repositories are built and installed into the local Maven
repository first, in this order: `spi-for-java`, then `adapter-platform-integration`. Then, here:

```bash
mvn spotless:apply
mvn install
```

`install` and not `install verify`: `install` already runs every phase `verify` has, so naming both
walks two lifecycles per module and reports every compiler warning twice.

Nothing in this repository needs Docker or a network: the Camunda 7 engine runs embedded, on an
in-memory database. That makes a full run cheap compared to the other adapters, and it is why both
platforms play the documented features through a real engine rather than a double, Spring Boot in
`integration-tests` and Quarkus in `quarkus/integration-tests`. A core which is correct says nothing
about a platform's glue ever calling it.

The build also measures itself. `test-coverage-report/coverage-gate` is the last module of the
reactor and fails below 85 percent of covered instructions per platform, while the rule is 90. See
[Test coverage](./README.md#test-coverage) for what the gate prints and why the threshold is not
the target.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc, the commit message and the pull request
included. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them.

## What is asked before the code is written

Where a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write
it and wait for the answer. An entry is never edited away: it stays, marked as superseded and
naming its successor, and the new decision takes the next free number.

The second question is the SPI. What this adapter implements is published by the platform
integration and served by three other adapters as well, so a change to the SPI is discussed there
and not worked around here. An adapter may refuse what its BPMS cannot do, with a message naming
the adapter, and that is a different thing from bending the contract.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what moved
and why it had to. It may cite an issue or a conversation, because it is a record of a moment
itself, which the code is not.

Check the numbers your branch hands out before you open it. Another branch may have taken the
decision number you used while you were writing, and once a pull request is merged a
`see decision 7` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

The *Publish to GitHub Packages* workflow builds and tests every pull request and publishes nothing
from a branch. A red check is a finding about your change. Read the log, which the workflow uploads
as a test report when the build fails, and fix what it says rather than pushing again to see
whether it goes away.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
