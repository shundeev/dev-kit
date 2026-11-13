# cc-safety-net, vendored

The PreToolUse hook of [cc-safety-net](https://github.com/kenryu42/cc-safety-net) (MIT, see LICENSE), at the version in
`PIN`: it refuses destructive commands and secret reads (dev-kit#20, ADR-0001 choice 4). Only the files its `hook`
command loads are here: `dist/bin/`. Its other commands (`doctor`, `-V`, …) need the full package and do not run
from this copy.

The files are byte-identical to the npm release whose sha512 is in `PIN`; `test/selftest.sh` downloads that release
and compares. To update:
1. `npm pack cc-safety-net@<version>` in an empty folder; check `npm view cc-safety-net@<version> dist.integrity`
   against the tarball's sha512; read the release notes.
2. Replace `dist/bin/`, `LICENSE` and `package.json` from the tarball, and `PIN`.
3. `test/selftest.sh`, then a pull request.
