# dist/windows

Output directory for Windows release artefacts produced by GitHub
Actions on `windows-2022` runners.

This directory is intentionally empty in the source repository — no
Windows binaries are checked in. At release time (when a `v*` tag is
pushed), `.github/workflows/build-installers.yml` produces:

- `Puklic-<version>.exe` — Compose Desktop / jpackage user-installer
  with bundled JRE (per-user install, no UAC elevation).
- `Puklic-<version>.msi` — Windows Installer package for MSI-driven
  deployment (Group Policy, SCCM, etc.). Stable `UpgradeCode`
  `{B3C4A1D0-E7F5-4D8A-9C3E-6F2A1B8D5E4F}` so each new version
  in-place-upgrades the previous instead of installing side-by-side.

Both artefacts are attached to the GitHub Release draft and
downloadable as workflow artefacts named `puklic-windows-x86_64`.

See `docs/07_roadmap/phases.md` §Platforms and architect report
`docs/03_infrastructure/architect-reports/2026-05-29-fp10-windows-platform.md`.
