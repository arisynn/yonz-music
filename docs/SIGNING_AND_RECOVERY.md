# Signing channel and owner recovery

Yono Music 1.1.0 starts the initial public production signing channel authorized by Karisyono. Package ID stays `com.yonzmusic.app`; public release code is 2. The production RSA-4096 certificate SHA-256 is:

`ebefa44cc5fba1292fec9fc25bb4840e41e501bb0958a86931c046fd1be523e5`

Keep this identity for all subsequent public releases. An APK name or version number alone cannot make a different certificate update-compatible.

## Persistence

The new PKCS#12 key and random credentials were generated outside the repository, with directory mode 0700 and private file mode 0600. The only working-tree configuration is ignored `signing.properties`. No keystore, password, token or private recovery material belongs in git, source archives, APKs or public Releases.

An owner-controlled copy was encrypted with GitHub's repository public key and stored in four **Actions repository secrets** in `arisynn/yonz-music`:

- `YONO_RELEASE_KEYSTORE_B64`
- `YONO_RELEASE_STORE_PASSWORD`
- `YONO_RELEASE_KEY_PASSWORD`
- `YONO_RELEASE_KEY_ALIAS`

Successful storage and the existence of all four secret names were verified using the repository API. Secret values cannot be read back through that API. This remote persistence survives this workspace and revocation of the publishing token. Recovery execution has not been tested in GitHub Actions during this delivery; do not confuse secret-metadata verification with a successful recovery drill.

## Make an independent owner backup

Use [the manual workflow template](../tools/ci/owner-release.yml.example) from an owner-authenticated checkout. It is deliberately a template: the supplied publishing credential lacks GitHub's separate `workflow` scope, and no new credential scope is requested or fabricated. Install it as `.github/workflows/owner-release.yml` using the GitHub UI or the owner's normal credential. Review the source before enabling a workflow that can access signing secrets. Protect the default branch and restrict secret access to trusted workflows.

Generate an **age** key on the owner's trusted device, keep its private identity offline, and enter only its public `age1…` recipient into the manual workflow input. The owner-only job restores secrets into temporary private files, builds with the same key, and optionally publishes an **encrypted** recovery archive as a short-lived Actions artifact. It never uploads an unencrypted keystore or passwords. Download the encrypted artifact, decrypt with the offline private identity, replace its temporary `storeFile` path in a local ignored configuration, and verify the certificate against the fingerprint above. Keep at least two independently protected copies. The template is supplied and reviewed, but not claimed executed.

## One-time transition from v1.0.0 test APK

The original v1.0.0 debug certificate is:

`3e8117db4ac9b9e71429136a4d687bdaec8a75f1c052ee7c153b6c62c2fda740`

Android correctly refuses an in-place update from that key to the production key. This is not a database migration failure. The separately labelled **Migration** build uses the original standard debug identity and code 2. For someone with that exact original test build, it supplies the non-destructive Room migration and **Import → Files & playlists → Export collection backup** before an owner-performed reinstall. Do not install it over the production channel. It is a debug tool and is never selected by `update.json`.

Export the JSON into a user-chosen document outside app-private storage; verify the backup exists. The app never uninstalls itself. Uninstall the old app only when the owner is ready, install the production APK, restore the collection metadata, and choose the music folders/files again to reconnect access. Original audio remains in place. Backups contain metadata and URI references, not the actual music, permission grants or download/installer journals. Repeated restore is additive and can add another playlist copy.

The `Dev` build has ID `com.yonzmusic.app.dev`, so testing it cannot replace the production application. Its GitHub production updater is disabled.
