# First public publication

The working source is being prepared for public use. **Do not push the existing local history.**
Earlier commits include real conversation fixtures and private deployment details. Deleting them
in a new commit does not remove them from history. Keep the original checkout privately and
publish a reviewed snapshot as a new Git repository.

## Decisions

- Destination: `maniche04/KandevMobile`, public.
- Source license: MIT (`LICENSE`). Bundled third-party assets retain their own licenses and
  provider marks retain their owners' rights.
- Signed APKs are published as GitHub releases by `.github/workflows/release.yml` with the
  maintainer's existing key (stored as repository secrets, see README). Release APKs are built
  without `google-services.json`, so no operator depends on the maintainer's Firebase project;
  FCM away alerts need a source build with the operator's own Firebase project. Release APKs
  update from the repository's GitHub releases, so users need no update server of their own.

## Authenticate the destination account

Sign in as the owner before creating the new repository:

```sh
gh auth login --hostname github.com --git-protocol https --web --scopes workflow
# Complete the browser flow while signed in as maniche04.
gh api user --jq .login  # Must print maniche04.
gh auth setup-git --hostname github.com
```

If that account is already stored, use `gh auth switch --hostname github.com --user maniche04`
then verify it again. This changes the CLI's active account on a shared host; coordinate with
other users. See [GitHub CLI authentication](https://cli.github.com/manual/gh_auth_login).
Do not paste access tokens into chat or commit them. Workflow permission is needed when pushing
the included `.github/workflows/checks.yml` with a classic token/OAuth credential.

The owner can alternatively create an empty repository on GitHub (without a README/license
initialization) and supply its SSH/HTTPS URL plus working push credentials. To keep another
account active globally, use a separately configured credential environment for publication.


## Prepare and review the snapshot

Run from the original checkout after finishing source edits:

```sh
python3 scripts/prepare-public-export.py ../KandevMobile-export
cd ../KandevMobile-export
```

The destination must not exist. This copies tracked and unignored new working files, including
uncommitted edits, with no `.git` directory. It excludes ignored local Firebase configuration,
build outputs and secrets. Review all exported files; ignore rules do not detect arbitrary
secrets in source.

Run a credential scanner against the **export directory**, and scan history separately if you
intend to publish any historical branch. For example, with a locally installed Gitleaks:

```sh
gitleaks dir . --redact
```

Known findings to review rather than treat as deployed credentials: RFC 8291's published test
vector in `relay/webpush.test.mjs`, and minified parser strings in vendored `xterm.js`. Do not
blanket-ignore all source or all test files. Never attach an unredacted scanner report to GitHub.

Check the export builds without `app/google-services.json` and without a signing file, then:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
node --test relay/*.test.mjs remote/*.test.mjs
```

Review the README images at full size and verify links, service paths and placeholders. Cloudflare
and FCM setup require the new operator's own accounts and should not be automatically provisioned
as part of building the APK. Hosted CI covers unsigned builds and service tests, not live cloud setup.

## Publish after owner decisions

From the reviewed export directory, substitute the actual destination:

```sh
git init -b main
git add .
git commit -m "Initial public release of Forge for Android"
gh repo create OWNER/REPOSITORY --public --source=. --remote=origin --push
```

Do not include the original `.git` directory, private configuration, signing keys or source-host
service environment. Verify GitHub Actions after pushing and review the public README rendering.
Then add the signing secrets (README › Publishing GitHub releases), enable private vulnerability
reporting, and push a `v<versionName>` tag. The workflow records the supported Kandev version,
FCM status, signing-certificate fingerprint and APK SHA-256 in the release. Do not upload
service-account JSON, phone enrollment files or Cloudflare credentials alongside an APK.

Later source changes are developed in this private checkout and copied into the public clone
(`prepare-public-export.py` into a new directory, then sync it over the public clone excluding
`.git`), so private history never reaches the public repository.
