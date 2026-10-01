# Mobile-Harness integration base

Imported from https://github.com/techjarves/Mobile-Harness at
`15177fcb12bbd2afc8e281a79fb8185bb5daa5b8`. The upstream MIT LICENSE is retained.
Individual bundled components retain their own licenses.

The repository's native PRoot and android-shmem dependencies are pinned as root
Git submodules under `mobile/native/`. They support optional **Agent CLI execution**,
not Android project compilation. No Termux app, terminal bootstrap or package
manager is embedded. Android compilation uses the independent Forge native engine.

This directory is an integration work in progress. The workspace build button now
uses Forge's separate native builder service. Android setup no longer installs the
upstream Linux Android toolchain; legacy private helper code still awaits cleanup.
Agent build-tool RPC, full dependency resolution and remaining product acceptance
are not complete. This must not be treated as a release-ready Forge product yet.
