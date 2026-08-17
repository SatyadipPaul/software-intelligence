# Publishing to Maven Central

Everything in this repository is prepared for a Central release. The three things that remain are
credentials and a signing key, which are the maintainer's to hold — no tooling here stores them, and
nothing publishes automatically.

## What is already done

- Coordinates: `io.github.satyadippaul`, version `0.1.0`. The `io.github.*` namespace is verified
  through the GitHub account rather than a DNS record, so no domain is required.
- Every POM field Central requires: name, description, url, licenses, developers, scm.
- The `release` profile attaches sources and javadoc for all ten modules and signs with GPG.
- `central-publishing-maven-plugin` with `autoPublish=false`, so a release lands in a staging area
  to be inspected and released by hand. Central coordinates are immutable: a version published by
  accident cannot be replaced or withdrawn.
- Verified locally: `mvn -Prelease package -Dgpg.skip=true` produces 10 sources jars and 10 javadoc
  jars with no javadoc errors.

## What only the maintainer can do

### 1. Verify the namespace

Sign in at https://central.sonatype.com with the GitHub account, and register the namespace
`io.github.satyadippaul`. Central asks for a public repository whose name is a verification code it
supplies; creating that repository proves ownership of the account.

### 2. Create a signing key

```bash
gpg --gen-key                                  # name and email; keep the passphrase safe
gpg --list-secret-keys --keyid-format=long     # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Central rejects unsigned artifacts, and rejects signatures it cannot verify against a public
keyserver.

### 3. Store the token

Generate a user token in the Central portal, then put it in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

This file holds a credential. Keep it out of the repository; `.gitignore` does not cover
`~/.m2/settings.xml` because it lives outside the project.

### 4. Publish

```bash
mvn -Prelease deploy
```

The build signs every artifact and uploads to the staging area. Review the deployment in the Central
portal, then release it there. Nothing reaches Central until that manual step.

## After the first release

Set the next development version so the working tree is never sitting on a released number:

```bash
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
```

## Worth weighing before you publish

Central coordinates are permanent. The graph schema has changed three times in the last week
(`0.1` → `0.2` → `0.3`), each time altering symbol identity, and the query layer's answers on a large
repository are still 6,447 symbols wide with precision unmeasured outside the fixture. Publishing
`0.1.0` is a reasonable way to say "early, expect change", but the artifacts and their coordinates
will outlive any regret about the API.
