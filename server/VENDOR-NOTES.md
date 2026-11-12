# Vendor notes: pi-rpc-web

## Provenance

| Item | Value |
|---|---|
| Upstream | https://github.com/tohlh/pi-rpc-web |
| Commit | `f8a1ba42d23e88ebcd6d44e8352e79ad4cdf4fd6` (v0.1.1) |
| Tarball | https://codeload.github.com/tohlh/pi-rpc-web/tar.gz/f8a1ba42d23e88ebcd6d44e8352e79ad4cdf4fd6 |
| Tarball SHA-256 | `1dd51db520177add5e03b9167019d769bf00034cde08ffcefb45570a6e45b18e` |
| License | MIT, Copyright (c) 2026 tohliheng (see `LICENSE`) |

## Files kept (top directory stripped)

The extension is not committed. `server/scripts/fetch-vendor.sh` downloads this
pinned commit and verifies the files with the manifest below.

`src/`, `ui/`, `CONTRACT.md`, `LICENSE`, `README.md` — 24 files, listed with
their SHA-256 in the manifest below.

## Files intentionally dropped

`.gitignore`, `media/` (README screenshots), `package.json`,
`package-lock.json`, `tsconfig.json`, `test/` (upstream's own test suite). None
of them are needed at runtime: `pi -e src/extension.ts` loads the TypeScript
sources directly through Node's type stripping, and the UI is plain
`index.html` + `css` + `js` with no build step and no npm dependencies.

## Modifications

None. Every vendored file is byte-identical to the release tarball (verified
with `sha256sum` against `tar xzOf`; 24/24 identical, 0 mismatched). Local
integration lives outside the vendor tree:

- `scripts/serve.sh` sets `PI_BIN`, `PI_HOME`, `PI_CODING_AGENT_DIR` and
  `PI_CODING_AGENT_SESSION_DIR` in the host environment, and passes
  `--rpc-web --rpc-web-lan -e vendor/pi-rpc-web/src/extension.ts`.
- The test-mode config seeding (`--test`) is a launcher concern, not a vendor
  patch; see `README.md`. It includes linking `~/.pi/agent/extensions` into the
  seeded config dir so spawned children load user extensions (pi 1.4.0 only
  looks in `<PI_CODING_AGENT_DIR>/extensions`), which is what lets the
  workspace-boundary approval dialogs reach the app.
- `sessions.list` in `src/bridge.ts` only scans
  `<piHome>/agent/sessions/<--encoded-project-->/` (the shape its rename/delete
  validation also requires), while pi writes session files flat into
  `PI_CODING_AGENT_SESSION_DIR`. The launcher therefore points the test-mode
  session store at the encoded project subdirectory instead of patching the
  vendored scanner; see "Session store layout" in `README.md`.
- The host is started with `cwd = PI_PROJECT_DIR` so the bridge's
  `cwd: hello.cwd ?? process.cwd()` fallback matches the documented project
  root.

## Verify

```sh
cd pi-app/server
sha256sum $(find vendor/pi-rpc-web -type f | sort)
```

## Integrity manifest

```text
704938a99b6f3ea580ce4eef9556e0247bd1923b57fb7574fc97395ebe09d53d  pi-rpc-web/CONTRACT.md
9ff46193521ba370553a7b4fed4e6a5926a72706a906c7b208ca8fdf27d0a699  pi-rpc-web/LICENSE
56d5926ccf82a5019750b741febed7aaf30725a4530f6519d98a2deb2454af40  pi-rpc-web/README.md
1a7c12a8a4b088f13b0491b4357cde8b0d4b7040edb9eacf6f4805b96adc1105  pi-rpc-web/src/bridge.ts
a9bf59e3b22841a00036d12bd86d5302557bd5818078deb745ef140f85e3af60  pi-rpc-web/src/extension.ts
0a63893cec8c776838441205b077c58d35041448a50a944d9a35f1f8a1d58444  pi-rpc-web/src/lan-auth.ts
9793425102248e74a961454a7ac50b5b8bb2104b02e880795fa031b4fd081b56  pi-rpc-web/src/server.ts
f8903a7f50f03f3e0c48223ec638f79fc62c4b33a1dec14533fe53c19587a6a4  pi-rpc-web/src/ws.ts
574b2b2826269c08e5365688aacb46b3fbb91fd4c76f91f2ddbdf5a9f230809a  pi-rpc-web/ui/css/base.css
f6975b574ed09325600972b457fa18f69b6f74acd8347731e7d3847b24367b1e  pi-rpc-web/ui/css/chat.css
efbc904436d3796fbdedd2e34d1e0162202b2cdd49d2a40633399696f69f8ebf  pi-rpc-web/ui/css/composer.css
59da790c80db87d2be5c536e442a4f6f3632ba008e3418d78038268989b996d1  pi-rpc-web/ui/css/dialogs.css
bb542b7283a2a7ff795569920e66d16ae0430d339cd33f979dedec424d713b0a  pi-rpc-web/ui/css/header.css
8bd09ea31509ad71336d03a06ca1c61974a7424a2d23162f86e47894e2a28ff9  pi-rpc-web/ui/css/sidebar.css
2ba8ab236f325a900322fd6746d390e53a8cfeca583b4c7198055441ce8c13db  pi-rpc-web/ui/index.html
1efa13b732dd661ef2c170e8d6fd7b83d201b7e1d8a2932ff36b0e959516d5d1  pi-rpc-web/ui/js/composer.js
003e0422530489004d84feaf3d63867b1cd0ed9423c8d9b165faf459160c1a09  pi-rpc-web/ui/js/dialogs.js
fc1f7b60c807d00e35f3560468643447b92ad14c676239b0ee78c4ea95b99d3f  pi-rpc-web/ui/js/header.js
7a041560d57db1346f16424cf2442a21c0f2e87b0cbde6fd2e72c002f9ee1ebb  pi-rpc-web/ui/js/main.js
d80a414ce10f72df3265d05e7caec0e61f9be498740dee3a57622253ec2da495  pi-rpc-web/ui/js/markdown.js
83a606569045738bf79409b8e47d0d08ced8759da1a81511a433238de9e6c77b  pi-rpc-web/ui/js/render.js
501806fe194ab719bd29427fc15a0800909f8ff03d08c7cc1020b5fe35f5b21e  pi-rpc-web/ui/js/sidebar.js
1549c7ba94348ed59e74fb4939e2193202fa4842cdb84e7ea72e618014236554  pi-rpc-web/ui/js/util.js
63d50637eb8b7fca8d71cad34e1ea8e32497c4510d7a7c809fb25e80772a35f2  pi-rpc-web/ui/js/ws.js
```
