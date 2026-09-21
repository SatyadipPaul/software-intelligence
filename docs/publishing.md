# Publishing to Maven Central

**This document is superseded. See [`RELEASING.md`](../RELEASING.md).**

It described how `0.1.0` was published: by hand, from a workstation, with `mvn -Prelease deploy`
and the signing key and Central token sitting in `~/.m2/settings.xml`. That worked, and it is how
`0.1.0` reached Central.

It is no longer how releases are cut, and following it now would skip three checks that exist for a
reason. The release workflow refuses a tag that does not match the version in `pom.xml`, refuses a
tag that is not an ancestor of `main`, and keeps the signing key in repository secrets rather than
on anyone's laptop. `mvn -Prelease deploy` run locally passes none of those.

The rest of what this file used to say now lives in `RELEASING.md`: the one-time portal and key
setup, the order of the steps, what is deliberately not published and why, and what to do when
something fails — including the case that has no remedy.
