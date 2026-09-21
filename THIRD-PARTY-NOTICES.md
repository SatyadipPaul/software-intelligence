# Third-party notices

Everything this project bundles is open source, and this file is the inventory that says so. It
covers what actually ends up inside `repo-intel.jar` — the shaded command-line artifact — because
that is the artifact where a licence question is hardest to answer by reading a POM.

Read from each artifact's own POM, not from a summary. Reproduce with:

```bash
mvn dependency:tree -pl apps/cli
```

## What the shaded jar contains

| Artifact | Version | Licence | Project |
| --- | --- | --- | --- |
| `org.eclipse.jdt:org.eclipse.jdt.core` | 3.46.0 | EPL-2.0 | [Eclipse JDT](https://projects.eclipse.org/projects/eclipse.jdt) |
| `org.eclipse.jdt:ecj` | 3.46.0 | EPL-2.0 | Eclipse JDT |
| `org.eclipse.platform:org.eclipse.core.resources` | 3.24.0 | EPL-2.0 | [Eclipse Platform](https://projects.eclipse.org/projects/eclipse.platform) |
| `org.eclipse.platform:org.eclipse.core.runtime` | 3.34.200 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.core.expressions` | 3.9.600 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.core.filesystem` | 1.11.400 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.core.contenttype` | 3.9.800 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.core.jobs` | 3.15.700 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.core.commands` | 3.12.500 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.text` | 3.14.700 | EPL-2.0 | Eclipse Platform |
| `org.eclipse.platform:org.eclipse.osgi` | 3.24.200 | EPL-2.0 | Eclipse Equinox |
| `org.eclipse.platform:org.eclipse.equinox.common` | 3.20.300 | EPL-2.0 | Eclipse Equinox |
| `org.eclipse.platform:org.eclipse.equinox.registry` | 3.12.600 | EPL-2.0 | Eclipse Equinox |
| `org.eclipse.platform:org.eclipse.equinox.preferences` | 3.12.100 | EPL-2.0 | Eclipse Equinox |
| `org.eclipse.platform:org.eclipse.equinox.app` | 1.7.600 | EPL-2.0 | Eclipse Equinox |
| `org.osgi:org.osgi.service.prefs` | 1.1.2 | Apache-2.0 | [OSGi Alliance](https://www.osgi.org/) |
| `org.osgi:osgi.annotation` | 8.0.1 | Apache-2.0 | OSGi Alliance |
| `net.java.dev.jna:jna` | 5.18.1 | LGPL-2.1-or-later **or** Apache-2.0 | [JNA](https://github.com/java-native-access/jna) |
| `net.java.dev.jna:jna-platform` | 5.18.1 | LGPL-2.1-or-later **or** Apache-2.0 | JNA |
| `info.picocli:picocli` | 4.7.6 | Apache-2.0 | [picocli](https://picocli.info/) |

`org.eclipse.osgi` embeds the Apache Felix resolver (Apache-2.0); its notices travel inside that
jar's own `about_files/`, which the shaded jar preserves.

## Not bundled

| Artifact | Version | Licence | Why it is not in the jar |
| --- | --- | --- | --- |
| `com.microsoft.onnxruntime:onnxruntime` | 1.20.0 | MIT | `<optional>true</optional>`. A consumer that never asks for a dense encoder is not made to take per-platform native binaries. |
| `org.junit.jupiter:junit-jupiter` | 5.11.0 | EPL-2.0 | Test scope. |
| `org.apache.maven:maven-core`, `maven-plugin-api`, `maven-plugin-annotations` | — | Apache-2.0 | `provided` scope in the Maven plugin; supplied by the Maven runtime. |

## Two things a reader of the jar needs told

**JNA is dual-licensed, and this project elects Apache-2.0.** Since version 4.0 JNA is offered under
LGPL-2.1-or-later *or* Apache-2.0 at the consumer's choice, and the jar therefore contains a
`META-INF/LGPL2.1` file that describes an option this project did not take. Downstream users receive
JNA under Apache-2.0. Nothing in `repo-intel.jar` is under a copyleft licence that reaches beyond
its own files.

**EPL-2.0 requires that source be obtainable.** The Eclipse components above are redistributed in
object form inside the shaded jar. Their source is published by the Eclipse Foundation at the
coordinates listed, and every one of them is available from Maven Central with a `-sources` classifier
at the exact version pinned here. EPL-2.0 §3.1(b) is satisfied by that; no modifications were made to
any of them.

## Model weights

The optional `embedding-model` artifact carries **no weights in this repository**, and its module
refuses to build unless a licence file and a provenance record sit beside them. See
[`modules/embedding-model/README.md`](modules/embedding-model/README.md). Weights are someone else's
work; redistributing them is a licensing act, and it is gated by a build rule rather than by a
checklist someone might skip.

## Keeping this file true

The root POM's enforcer carries an allow-list of every dependency coordinate named above. A new
dependency — direct or transitive — fails the build until it is audited and added here. A licence
inventory nobody can re-run is not an inventory.
