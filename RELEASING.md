# Releasing

Releases go to **Maven Central** under the `io.github.satyadippaul` group, through the
[Central Portal](https://central.sonatype.com/). They are cut by tagging `main`; CI does the rest.

**A coordinate published to Central is permanent.** It cannot be overwritten, and it cannot be
deleted. `0.1.0` already means one set of bytes and always will, so everything below is arranged so
that the irreversible step is the last one and a human takes it deliberately.

## One-time setup

Already done for `0.1.0`, which is on Central. Repeat only if the token is rotated or the signing
key is replaced. None of it lives in the repository.

### 1. Claim the namespace

Sign in to [central.sonatype.com](https://central.sonatype.com/) with GitHub, and add the namespace
`io.github.satyadippaul`. Signing in as the GitHub user of the same name verifies it automatically;
no DNS record is needed for an `io.github.*` group.

### 2. Generate a user token

Central Portal → your account → **Generate User Token**. It prints a username and a password once.
These are not your account password, and they can be revoked without changing it.

### 3. Create a signing key

Central requires every artifact to be signed, and the public key must be on a keyserver it checks.

```bash
gpg --full-generate-key                  # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
```

Put the contents of `private-key.asc` into the secret below, then **delete the file**.

### 4. Store four repository secrets

Settings → Secrets and variables → Actions:

| Secret | What it holds |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | the token username from step 2 |
| `CENTRAL_TOKEN_PASSWORD` | the token password from step 2 |
| `GPG_PRIVATE_KEY` | the full armoured private key, `-----BEGIN` line included |
| `GPG_PASSPHRASE` | the passphrase for that key |

Nothing else needs to exist anywhere. In particular there is no `~/.m2/settings.xml` in this
repository's workflow: `actions/setup-java` writes it from the secrets and throws it away.

## Cutting a release

### 1. Land the work on `main`

The release workflow refuses a tag that is not an ancestor of `main`, because a release built from
an unmerged branch publishes code nobody reviewed, at coordinates that can never be reused.

### 2. Settle the version and the changelog

`pom.xml`'s `<version>` is what gets published, and the workflow refuses a tag that disagrees with
it, so drop the `-SNAPSHOT` before tagging. Update the version in the README's Maven snippets too — CI fails until the
README matches `pom.xml`, so it cannot be forgotten. In `CHANGELOG.md`, rename `## [Unreleased]` to the
version and its release date, open a fresh `## [Unreleased]` above it, and add the two link
references at the foot of the file.

### 3. Rehearse

Actions → **Release** → *Run workflow*, with **dry run** left ticked. This runs `mvn -Prelease
install` on a clean checkout: the full verify gate, the sources and javadoc jars, and real GPG
signing — everything a release does except the one step that cannot be undone. If it goes green, the
only thing left untested is the upload.

### 4. Tag

```bash
git checkout main && git pull
git tag -a v0.2.0 -m "0.2.0"     # the version in pom.xml, with a v prefix
git push origin v0.2.0
```

The tag push starts the workflow. It verifies, builds, signs, uploads to a **staging** repository,
and attaches `repo-intel.jar` to a GitHub release with that version's changelog section as the
notes.

### 5. Release the staged deployment by hand

Open [central.sonatype.com/publishing](https://central.sonatype.com/publishing). The deployment sits
there validated but unpublished, because `autoPublish` is deliberately `false` in the release
profile. Check the component list, then **Publish** — or **Drop** it, which is the last moment at
which dropping is possible.

Artifacts appear on `repo.maven.apache.org` within roughly 30 minutes, and in the Central search
index within a few hours.

## What gets published, and what does not

| Artifact | Published |
| --- | --- |
| `model`, `analyzer-java`, `framework-spring`, `architecture`, `pipeline`, `index-tree`, `embedding`, `query-engine`, `session`, `evaluation`, `visualization` | yes |
| `cli` | yes, as a **thin** jar — the dependencies come from Maven |
| `repo-intel-maven-plugin` | yes |
| `repo-intel.jar`, the runnable shaded CLI | **not to Central.** It is attached to the GitHub release instead. |
| `embedding-model`, the packaged weights | **no.** The module only builds when weights are present, and refuses to build unless the model's own `LICENSE` and a `PROVENANCE.md` sit beside them. That licence has not been verified from the model's own sources — see [`modules/embedding-model/README.md`](modules/embedding-model/README.md). |

The shaded jar is 15 MB and would be stored forever, per version, on infrastructure someone else
pays for. A GitHub release asset is the right home for it, and the workflow attaches it
automatically so the two never drift apart.

## If something goes wrong

- **Validation fails in the portal.** Nothing has been published; fix and re-tag with a new patch
  version. Never move a tag that CI has already acted on.
- **Signatures rejected.** The public key is not on a keyserver Central checks, or is too fresh.
  Re-send it and wait, then re-run the workflow.
- **Published the wrong thing.** It cannot be withdrawn. Publish a corrected version and mark the
  bad one in the changelog. This is the reason for the dry run and the manual publish step.

## Publishing somewhere other than Central

For an internal Artifactory or Nexus, add a `distributionManagement` block with that repository's id
and URL, put matching credentials in `~/.m2/settings.xml`, and use `mvn deploy` without the
`central-publishing-maven-plugin`. Nothing else about the build changes — the signing, the sources
and javadoc jars, and the dependency allow-list are all independent of where the artifacts land.
