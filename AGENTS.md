# EOS Camera Roll

Keep all frontend design, implementation, debugging, testing and review in the main conversation's selected model. Do not delegate frontend work unless the user specifically requests it.

Every deliverable Android build must be published to GitHub Releases in Wizhill05/EOS-Camera-Roll. Use scripts/release.sh locally or the Build and publish APK workflow. Bump versionCode/versionName and write docs/releases/VERSION.md before a new release. Never replace an existing version's APK with different bytes. Verify the published artifact and preserve the package/signing identity for in-place updates.

Run the relevant Android workflow tests and host protocol/media checks. Inspect native gallery renders for UI changes. Be explicit about simulated tests versus physical-camera, handset and Lightroom evidence.

Do not publish local logs, working notes, device addresses, credentials, downloaded camera media, proprietary Canon research/SDK files or signing keys. Keep vendored LibRaw sources and notices with the native binary. Regenerate the native binary after native-code changes.

The user accesses the Azure VM through Codex. Published APKs belong on GitHub Releases; temporary VM services can be shared through Tailscale without port forwarding.

Gallery browsing must never fetch original media or original-file headers to determine thumbnails or orientation. Display cached/small camera thumbnails first; optional rotation metadata must be lower priority, small and time bounded. Full originals require an explicit viewer/save/Lightroom action. Keep cold/warm browsing traffic regression checks, and do not call simulated request-count checks a physical-camera throughput benchmark.
