# Q9 release resilience session log — 2026-08-29

## Inventory and design

- Expanded scope from Super Alarm to every Q9 Labs app and both the MacBook Air and agents Mac mini.
- Completed a read-only inventory without printing secret values. The Mac mini holds valid Apple Distribution identities; the MacBook Air does not.
- Confirmed 15 apps in App Store Connect and found signing material spread across local keychains, app folders, private-key folders, and the 1Password `dev` vault.
- Drafted `/Users/macmini/code/q9-release-resilience-spec.md`. The proposed final state uses 1Password as the source of truth, disposable signing environments, protected GitHub release jobs, and clean-builder recovery drills before any local cleanup.
- Folded an independent security critique into the design. Added owner and offline break-glass recovery, explicit containment for the shared Apple signing blast radius, distributable-target inventory, staged store-acceptance drills, and stronger workflow and keychain isolation.

## Execution started

- Narrowed discovery to releasable products and release assets present on the MacBook Air or agents Mac mini. Store catalogs are verification sources, not scope expansion.
- Reused the existing `Mobile App Releases` vault for convenience.
- Added and recovery-verified the Super Alarm Android upload keystore, passwords, alias, package, file checksum, and certificate fingerprint in one 1Password item. The original local files remain untouched.
- Recovery-verified the existing Apple Distribution `.p12` and Super Alarm provisioning profile. Their SHA-256 certificate fingerprints match.
- Added the Sentry release-upload credential to `Mobile App Releases` and installed Sentry CLI 3.7.0 on both Macs.
- Added secret-free CI and protected signed-build workflows plus temporary Android and iOS signing scripts. CI service-account installation remains pending because the current 1Password CLI identity cannot create another service account.
- Created `/Users/macmini/code/q9-release-local-inventory.md` to track every locally discovered product through recovery and CI rollout without expanding to cloud-only products.

## Super Alarm recovery drills

- The Mac mini reconstructed the Android keystore exclusively from 1Password, matched the repository manifest, passed unit tests, built the release AAB, and matched the certificate embedded in the AAB to fingerprint `56:95:CD:E1:B7:4A:B1:8D:12:B8:B9:94:73:58:92:41:94:EF:C8:47:48:13:A7:C4:4D:8E:53:E9:EE:85:31:3C`.
- The MacBook Air independently completed the same Android recovery, tests, signed build, and embedded-certificate verification after its ignored local Android SDK path was corrected.
- The Mac mini reconstructed a temporary Apple keychain and profile exclusively from 1Password, archived and exported the App Store IPA, and matched the embedded signing certificate to fingerprint `97:B9:31:DA:10:8D:7E:DE:A6:FC:88:32:85:A6:7A:E9:71:3B:28:49:27:AB:9A:BC:5A:71:BA:9D:91:8B:69:98`.
- The iOS gate then stopped at Sentry symbol upload because the existing release token is revoked and returns HTTP 401. The signed IPA and dSYM archive were preserved for retry; no store upload occurred.
- The GitHub `production-signing` environment now exists and accepts deployments only from `main`. Its 1Password secret is intentionally absent until a read-only service account is created.

## Super Alarm GitHub baseline

- Committed and pushed the recovery manifest, signing scripts, protected signed-build workflow, and normal CI workflow to `main`.
- The required code review found no actionable correctness defects.
- GitHub run `33257798175` passed the Android unit-test/debug-build job on Ubuntu and the iOS simulator-build job on macOS.
- Updated the pinned GitHub actions to their current Node 24 releases after the first runs exposed runtime deprecation warnings. The final baseline completed without those warnings.

## Protected Super Alarm Android release

- Created `Q9 Mobile Release CI 2026-08-29` as a 1Password service account with read-only access to `Mobile App Releases`, no environment access, and no vault-creation permission.
- The first one-time token appeared in a browser accessibility snapshot. It was immediately invalidated, replaced, saved in 1Password, verified through the CLI, installed as the GitHub `production-signing` environment secret, and removed from its mode-600 temporary path.
- GitHub run `33258553158` reconstructed the Android signing material from 1Password, passed the release tests and build, verified certificate fingerprint `56:95:CD:E1:B7:4A:B1:8D:12:B8:B9:94:73:58:92:41:94:EF:C8:47:48:13:A7:C4:4D:8E:53:E9:EE:85:31:3C`, and uploaded the signed AAB.
- Independently downloaded and verified the GitHub artifact. Its AAB SHA-256 is `52734b79528c83bc87de8365264ba0aba154d1435338947038abdbb40ff936f4`, and its embedded certificate matches the release manifest.
- The latest normal CI run `33258545977` also passed Android and iOS after all action pins were updated to Node 24 releases.
- Sentry token replacement remains blocked because Sentry does not load under Helium's ad blocker and Chrome security policy prohibits automated access to extension settings. Manual allowlisting of `q9labs.sentry.io` is required before the protected iOS workflow can complete its symbol upload.

## Cross-app migration

- Limited the migration to releasable apps found on the Mac mini and MacBook Air. DiabMart, EmanTime, Hasan Headquarters, Spend, Diet Bite Life, and Morning Podcast are explicitly excluded and unchanged.
- Rotated the shared read-only `Q9 Mobile Release CI 2026-08-29` service-account token after diagnostic output exposed the prior token. The replacement can read only `Mobile App Releases`, is denied access to `dev`, and is installed as an exact no-newline `production-signing` environment secret in every included GitHub repository.
- Added recovery manifests, temporary credential materialization, fail-closed signed-build workflows, and normal mobile CI where each repository and its available signing material allowed it.
- Green hosted CI is proven for Mobile Dictation, TodoBar, Bellfeed, Chalk, Bolo, and Track. TodoBar's protected signed build is also green; Bolo and Track's protected jobs correctly stop before signing because production assets are missing or unverified.
- SIP and Cue Capture have local recovery tooling and successful local build proofs, but neither has a GitHub remote, so hosted CI cannot be enabled yet.
- Collab Dialer's mobile app exists on `plan/mobile-app`, not its default branch. Q9 Recruiter's mobile product history exists only in local Git history. Both need a product-history decision before protected default-branch signing can be enabled.
- Murmur release files are isolated in PR 27 because `master` is protected. Ava's clean local signed Android and iOS recovery drills passed; hosted CI fixes are running.
- Murmur PR 27 reached all-green checks and has policy-compliant squash auto-merge enabled; it waits for the human branch-policy gate.
- Ava's hosted signed iOS proof is green. GitHub now rejects all new Ava jobs before runner allocation because the account's Actions budget is $0 with stop-usage enabled, so the corrected hosted Android jobs cannot run until Hasan approves a nonzero budget or another runner is used.
- Collab Dialer's typecheck/tests and hosted iOS build passed. Its first Android build exhausted runner disk while compiling all ABIs. The app-branch workflow now cleans unused runner toolchains, disables Gradle caching, and limits the debug proof to `x86_64`; GitHub rejected the corrected rerun before runner allocation under the same billing gate.
