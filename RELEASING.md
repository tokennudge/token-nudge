# Releasing TokenNudge to Maven Central

Artifacts are published to [Maven Central](https://central.sonatype.com/) under the
`io.github.tokennudge` namespace, which is verified through the **tokennudge** GitHub
organisation. Three modules are published:

- `tokennudge-core`
- `tokennudge-camunda7`
- `tokennudge-junit5`

`tokennudge-examples` is deliberately excluded (`maven.deploy.skip`); it is a demo, not API.

## One-time setup

### 1. Central Portal account and token

1. Sign in at [central.sonatype.com](https://central.sonatype.com/) with the GitHub account
   that owns the **tokennudge** organisation.
2. Confirm the `io.github.tokennudge` namespace is verified.
3. Generate a user token: **Account → Generate User Token**. You get a token username and
   password, not your login credentials.

### 2. A published PGP key

Central rejects unsigned artifacts, and the public key must be discoverable on a keyserver.

```bash
gpg --full-generate-key                 # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```

Keep the private key and its passphrase out of the repository. Nothing here references them.

### 3. `~/.m2/settings.xml`

The `publishingServerId` in the release profile is `central`, so the server id must match:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
        <gpg.passphrase>YOUR_KEY_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```

## Releasing

The version in `pom.xml` is the version that gets published. Modules inherit it, so it is set
in one place.

1. **Check the tree is release-worthy**, including integration tests against both engines:

   ```bash
   ./mvnw -B clean verify
   ./mvnw -B verify -pl tokennudge-camunda7 -am -Dtokennudge.it.engine=cibseven
   ./mvnw -B -Prelease clean verify        # sources and Javadoc with doclint; no key needed
   ```

   `release` deliberately needs no PGP key, so anyone can run it. Signing and uploading live
   in the separate `central` profile used in step 3.

2. **Set the release version** if it is still a snapshot, then commit:

   ```bash
   ./mvnw -B org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   git commit -am "Release 0.1.0."
   ```

3. **Publish.** This signs every artifact and uploads a *staged* deployment. Both profiles are
   needed: `release` builds the sources and Javadoc jars Central requires, `central` signs and
   uploads them:

   ```bash
   ./mvnw -B -Prelease,central clean deploy
   ```

4. **Release it.** Open [central.sonatype.com/publishing](https://central.sonatype.com/publishing),
   check the deployment validated, and press **Publish**. `autoPublish` is `false` on purpose,
   so an accidental `deploy` never becomes a permanent release. Central releases are immutable:
   a published version can never be replaced, only superseded.

5. **Tag and push:**

   ```bash
   git tag -a v0.1.0 -m "TokenNudge 0.1.0"
   git push origin main --follow-tags
   ```

6. **Open the next development version:**

   ```bash
   ./mvnw -B org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "Open 0.2.0 development."
   ```

Artifacts usually appear on Maven Central within ten to thirty minutes of publishing, and on
search a little later.

## Releasing from CI

`.github/workflows/release.yml` runs the verification steps for every `v*` tag, and stages a
release only when started manually from **Actions > Release > Run workflow**. Staging is kept
off the tag trigger on purpose: re-pushing or moving a tag would otherwise re-upload a version
that is already published, which Central rejects as a duplicate. It needs four repository
secrets:

| Secret | What it is |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | Central Portal token username |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armoured private key: `gpg --armor --export-secret-keys <KEY_ID>` |
| `GPG_PASSPHRASE` | That key's passphrase |

The workflow still stages rather than publishes, so the final **Publish** click stays manual.

## Versioning

Semantic versioning. Anything under `io.github.tokennudge.spi` is an extension point for
adapter authors and counts as public API; `…​.internal` and the package-private classes beside
`TokenNudge` do not.
