# EOS Camera Roll 0.6.5

An Android app for browsing, culling and copying original photos and recorded videos from the Canon EOS R50 V over CCAPI. Install this APK over the existing Camera Link Lab / EOS Camera Roll app. It uses the same package and development signing certificate. Do not uninstall first if you want to keep connection preferences and local ratings.

## Connect

Keep the working camera/phone hotspot arrangement. Open EOS Camera Roll, tap Connect, and enter the current address shown by the camera. For the camera connected to your phone hotspot, use Phone hotspot / Automatic. If the phone joins camera Wi-Fi, select that Wi-Fi route instead. Keep the account switch off for your already-working authentication-disabled setup. The app remembers the address and folder permission, but does not store account passwords.

The library discovers the camera's advertised contents API, including the developer-list fallback, and enumerates storage, DCIM folders and XFVC reels with pagination. Folder failures are reported as a partial library. No camera server, computer or Bluetooth connection is needed after the hotspot and CCAPI connection are established.

## Browse and cull

The library uses three-column square photo tiles with subtle corners, a red accent, and floating Library / Rated / Saved navigation. Folder and rating filters are in the filter sheet; saving and card copy are in the red download button’s sheet. Dark mode is the default. Library options offer Dark, Light and System appearance. The gallery has All / Photos / Videos, a camera-folder selector, and a minimum-star filter. Filename labels and sorting are in the options menu.

The last successful library and normalized thumbnails are cached across restarts. Press Refresh to reconnect; a failed connection retains the cached library and is shown separately from an empty card. Thumbnails are shown before optional rotation metadata. Browsing never probes or downloads original files. Rotation uses embedded thumbnail EXIF or small camera metadata, and normalized thumbnails are cached on disk. If the camera omits both, opening the photo loads its original on demand and updates the cached thumbnail with the correctly rendered image.

Tap a thumbnail to open it. The viewer has previous/next, Save, file information, and a 1–5 star bar. Tap the selected rating again to clear it. Hold a grid item to select multiple items, then rate or save the selection. Camera ratings are imported from file information. A star edit is written to both members of a pair only when the camera advertises contents PUT. Each file must read back the requested rating before the app labels it a camera rating. Offline, unsupported or unconfirmed edits stay phone-only, including pre-existing app ratings. This does not promise that Lightroom will import Canon ratings.

To save everything rated at least four stars, choose a minimum of four stars in Filters, then open the download sheet and choose Save this view. Photos/Videos and the folder filter also apply to Save view. Copy card ignores the view filters and offers all photos, all videos, or both in a new timestamped folder.

Ratings and saved receipts use the camera's reported serial number when available, plus the card path independent of the CCAPI version. When the camera does not expose its identity, the camera address is the fallback. Reusing the same card paths after formatting/replacing files can reuse these local labels. A rating is not a content fingerprint.

## Gallery request policy

A cached thumbnail is returned without a camera request, including after refresh or process restart. An uncached tile requests `kind=thumbnail`; optional small `kind=info` JSON is lower priority, bounded to 64 KiB with short timeouts, and skipped after the camera reports no rotation for that format or a metadata request fails. No original/header or display-image GET is issued by grid browsing. Full originals and display previews are requested only for a photo that is opened, saved or sent to Lightroom. Recorded videos have no playback or streaming path.

One gallery worker handles network requests, with at most 16 waiting jobs. Thumbnail jobs precede metadata. Recycled tiles are skipped and stale queued work is discarded during scrolling, pausing, refresh, backgrounding and transfers. Opening the viewer cancels grid requests. Thumbnail completion updates one indexed row instead of rehashing/scanning the entire library.

Refresh re-enumerates the camera library and reuses existing thumbnail images; it does not revalidate every cached file. If a camera file is replaced or rotated at the same path, opening that photo updates its cached thumbnail. Existing star ratings, download receipts and destination permissions remain independent of this disposable image cache.

## Full-resolution photos

The viewer first displays a quick camera preview. It then privately caches the original image and loads the full image for zooming. JPEG decoding uses the original dimensions and EXIF orientation. CR2/CR3 uses bundled LibRaw on the phone, including EOS R50 V support. The Full resolution label appears only after that complete image is ready, with the rendered pixel dimensions. Pinch to zoom, drag to pan, and double tap to switch between fit and pixel inspection.

RAW rendering takes more memory and time than a camera JPEG preview and can differ from Canon's picture style, crop or lens correction. Unsupported formats, allocation failures or decoder failures retain the preview with a Preview only message. The original remains available to save. Images above 64 megapixels are excluded from full decoding in this build. The APK includes the arm64-v8a native decoder for modern 64-bit Android phones.

The original-photo cache is private and disposable. It keeps approximately 160 MiB, allowing the current image to exceed that budget. It is separate from downloads and is not a saved mark. A Lightroom handoff removes its corresponding cached original after staging it.

## Default download folder and saved marks

Open the download sheet and choose a writable download folder once. Android grants persistent access. Save, Save view and selection Save use that folder directly thereafter. If access is revoked or the folder is unavailable, select a folder again from the download sheet or options menu.

Copy card creates a new `Camera yyyy-MM-dd HH-mm-ss` directory under that folder. Files are copied sequentially; the provider can adjust duplicate names. Normal repeated saves check the existing receipt and skip a still-present file in that destination. Copy card deliberately makes a fresh copy in a new directory.

A green check means an original download completed through this app. The receipt is written only after the output stream closes successfully. It includes destination URI, byte count and SHA-256. Earlier versions' downloads and Lightroom imports have no such receipt. The check is a historical completion mark, not continuous monitoring of later deletions. A subsequent Save checks whether the recorded destination still exists before skipping it.

Transfers show a byte/progress bar in the library and viewer. The foreground notification supports Cancel; a separate completion or failure notification remains afterward. Allow notifications when Android asks. An in-app message reports completion on return. The engine validates Content-Length when supplied and computes SHA-256 without changing the original. Completed files remain after a later file fails. Incomplete new files are removed when the document provider permits it. There is no transfer resume after Android kills the process.

## Lightroom

Open a photo and tap Lr. The app obtains the original, stages it privately, and sends Lightroom a read-only content URI. No permanent Downloads or gallery copy is created for this action. If the viewer already cached the original, it can be reused after checking camera metadata. A hard link avoids another physical copy where the filesystem supports it; otherwise the app streams a temporary copy.

Lightroom must be installed. It controls its own import, editing and storage. EOS Camera Roll cannot force Lightroom's import UI, confirm that an import completed, save edited results back into this app, or guarantee what Lightroom deletes locally or in its cloud account. This uses Lightroom's documented third-party import workflow, not a private Adobe API:

- https://helpx.adobe.com/lightroom/desktop/using/open-photos-lightroom-android.html
- https://helpx.adobe.com/lightroom/mobile/add-and-capture-photos/add-and-import-photos/add-photos-from-third-party-apps.html

The handoff file is removed 60 seconds after the last reader closes it, provided no reader has reopened it. If the app process is interrupted, startup cleanup removes handoff files older than 24 hours. Lightroom's imported copy is then managed inside Lightroom. Lightroom handoff does not set the green saved mark. This release hands off individual photos, not video batches.

## Videos

Video playback is removed. Opening a video shows the cached camera thumbnail, local stars and Save. There is no player, seek bar, range probe, Download & play, autoplay or external-player handoff. Save explicitly copies the original to your selected folder with progress and a result notification. No video is downloaded just by opening it.

HTTP clients for the same camera share one request slot through response cleanup. Small thumbnail responses finish when the viewer opens, without routine interruption. Automatic loading stops after a connection failure; cached photos remain visible. Use Refresh after checking the camera connection. An already-unresponsive camera may still require a camera-side CCAPI disconnect/reconnect or power cycle; the app cannot reset its communication service. See [the investigation](docs/network-lockup.md).

## Build and verify

Use JDK 21 and the installed Android SDK, then:

```
./gradlew :app:assembleRelease :app:lintRelease :app:testDebugUnitTest
bash check-protocols.sh
bash check-ccapi-links.sh
bash check-media.sh
```

The source includes the built arm64 library. To rebuild it, download the official Android NDK r27d, set `CAMERA_NDK_TOOLCHAIN` to its `toolchains/llvm/prebuilt/linux-x86_64` directory, and run `python3 native/build-native.py`. The build uses the source list from LibRaw's Makefile.am and 16 KiB ELF alignment. LibRaw is pinned to commit `a9a14b1970d188c25dade81f8326ff00a6e33649`, distributed under its CDDL option. Exact source, copyright notices and license are included in the source archive; the app's About dialog exposes the license. Release is non-debuggable and signed with the original local development certificate, not a Play Store distribution key.

`python3 native/check-raw.py` downloads the public-domain EOS R50 V test CR3 from raw.pixls.us, verifies its published SHA-256, and exercises the same full-resolution LibRaw settings on the host. Android workflow tests render actual native Android views with Robolectric, load full-resolution JPEG from a local test camera, exercise ratings and saved marks, and check document-provider transfers and Lightroom staging. These are simulated Android tests, not a test of Lightroom installed on the physical phone or the phone's video codecs. See docs/verification.md for the completed checks.

Scope remains images and recorded videos. No capture, camera control, camera deletion, cloud account or upload features are included.

## Paired files, capture settings and filters

Same-folder, same-stem RAW/JPEG files appear as one photo, using the JPEG thumbnail. Ambiguous groups are not merged. The fixed RAW/JPEG tabs choose the viewed original; viewer Save and Lightroom use that chosen file. Bulk Save from the combined/pairs view expands to both originals. RAW-only or JPEG-only filtered bulk saves include only that format. Copy card still includes every physical file. A grouped saved mark requires both originals; per-format views and the viewer show the chosen file's receipt.

The viewer displays available shutter speed, aperture and ISO. Information shows available focal length, lens, camera, capture date, exposure compensation, white balance, flash, metering and dimensions. JPEG details use local original EXIF; RAW exposure/lens details use LibRaw after the original is opened. No grid action fetches original-file headers. Missing metadata is not replaced with the camera's current shooting settings.

Filters include file format, paired/unpaired files, saved state, minimum/exact rating, unrated/unknown ratings, camera/phone rating source, ISO bands and folders. Capture filters use metadata already read from originals, and exclude unknown ISO values. Camera ratings on cached or offscreen photos can be refreshed explicitly from Filters. This action is serialized, uses 64 KiB file-information responses, can be stopped, and offers continuation after a bounded batch. Normal cached browsing does not revalidate every file.
