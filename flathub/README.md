# Flathub submission files

Everything needed to submit pkgfind to [Flathub](https://flathub.org). These
files are self-contained and do **not** change the app's existing packaging
(the `pkgfind/flatpak/` Flatpak, the Homebrew formula, or the Windows installer
all keep working unchanged).

## Files

| File | Purpose |
|---|---|
| `io.github.yarden2012.pkgfind.yaml` | The Flathub manifest |
| `io.github.yarden2012.pkgfind.metainfo.xml` | AppStream metadata (name, description, screenshots, release) |
| `io.github.yarden2012.pkgfind.desktop` | Desktop entry |
| `io.github.yarden2012.pkgfind.svg` | App icon |

## Before submitting — two things to finish

1. **Add a screenshot.** Run the app, capture the window, and commit it as
   `flathub/screenshot-1.png` (the metainfo already points at it). Until then,
   `appstreamcli validate` reports one warning: `screenshot-image-not-found`.
   Everything else in the metainfo validates clean.

2. **Decide on the app id.** These files use `io.github.yarden2012.pkgfind`,
   because Flathub requires an id under a domain or code host you control, and
   the current `dev.rolo.pkgfind` implies ownership of `rolo.dev`. The manifest
   rewrites the id at build time (via `sed`) so the upstream repo doesn't have
   to change. If you'd rather rename the app everywhere for good, that's a
   larger change to `app.py`, the desktop/metainfo files and `pkgfind/flatpak/`
   — say the word and I'll do it.

## The honest caveat

pkgfind works by driving the **host's** package managers (`flatpak`, `dnf`,
`rpm-ostree`, `brew`, distrobox …) through `flatpak-spawn --host`, enabled by:

```
--talk-name=org.freedesktop.Flatpak
```

That is a broad permission — effectively "run commands on the host" — and it is
the single thing Flathub reviewers scrutinise most. An app whose whole purpose
is managing the host sits in tension with Flatpak's sandbox, so expect this to
be questioned, and be prepared for the possibility that it isn't accepted in
this form. The direct downloads (the GitHub release and the `.flatpak` bundle)
are unaffected either way.

## How to submit

1. Fork [`github.com/flathub/flathub`](https://github.com/flathub/flathub).
2. Create a branch **off the `new-pr` branch** (not `master`).
3. Copy the four files above into the repo root of your branch.
4. Open a pull request against the `new-pr` branch.
5. A bot builds and test-installs it; a reviewer then reviews (this is where the
   permission question lands). Once merged, Flathub creates a dedicated
   `flathub/io.github.yarden2012.pkgfind` repo and the app goes live; later
   updates are PRs to that repo.

Validate locally first with:

```sh
appstreamcli validate io.github.yarden2012.pkgfind.metainfo.xml
desktop-file-validate io.github.yarden2012.pkgfind.desktop
```
