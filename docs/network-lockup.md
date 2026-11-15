# Camera lockup investigation

## What the evidence establishes

The failure still occurs on 0.6.3 when Play is tapped, and the camera becomes reachable again after a camera restart. The build environment has not captured that incident on the physical camera or phone.

The 0.6.3 source shows that Play always starts with a `Range: bytes=0-0` request to the original video, then gives Android's player access to more ranges. The playback time limit runs after that first request. Removing the old full-video buffer reduced one source of traffic but left this request pattern in place.

The networking code also allowed thumbnail, photo-preview, file-information and transfer sessions to open separate connections to the same camera. Synchronizing a method on one session did not protect the other session instances. Entering a viewer cancelled an active thumbnail instead of allowing that small response to finish. Repeated transfer updates could request cancellation again. A thumbnail queue could then continue against a failing camera, and a folder scan could swallow a transport failure and try more folders.

These are confirmed properties of the app. They are not proof of a specific Canon firmware defect.

## Why a phone-side timeout was insufficient

A client timeout stops waiting or requests socket cancellation. It is not a command that resets the camera's CCAPI service. A camera could remain occupied or fail to recover from a partial-file request, an interrupted response or overlapping requests after the client gives up. Restarting the camera resets more than the app's player: it also restarts communication and Wi-Fi state.

The observed recovery after a camera restart is consistent with a camera-side communication problem. It does not distinguish a firmware request-handling failure from a camera/Wi-Fi problem. There is no physical request trace or camera diagnostic log proving which one occurred. We should not call a particular firmware bug confirmed, or promise that an app refresh can repair it.

The earlier fixtures checked range bytes, cancellation callbacks and simulated MediaPlayer state. They did not exercise the R50 V's HTTP implementation or the phone's real video decoder. Passing those checks did not establish that streaming was safe on this camera.

## What 0.6.4 removes

Video playback is deleted, rather than disabled behind a button. There is no MediaPlayer, MediaDataSource, remote-video source, range probe, seek bar, Download & play action, autoplay after saving, or external-player handoff. Video tiles show their format instead of a play symbol. Opening a video displays its cached thumbnail and offers ratings and Save.

The APK release check inspects the actual DEX files. It rejects player/data-source/metadata-retriever references and executable HTTP Range header constants. It rejects the published 0.6.3 APK and accepts the replacement. AndroidX's unrelated Kotlin Range annotations are not treated as an HTTP header.

## Other networking changes

- All app HTTP clients for the same camera origin share one connection slot. The slot stays owned through response cleanup. Cancelling a request does not free it early for another request to overtake a response that is still closing.
- Requests ask to close their HTTP connection when finished. Normal small thumbnail responses finish when the gallery pauses; entering the viewer no longer routinely aborts them. A pause also becomes idempotent.
- Eager viewer information requests are removed. Photo previews keep their separate cancellable session, and stop when the app leaves the foreground. Videos use cached covers without starting a file or metadata request.
- Small responses have inactivity timeouts, a scheduled total deadline and cancellation checks between reads. Thumbnail responses are limited to 2 MiB, rotation JSON to 64 KiB, and listing JSON to 4 MiB. A known oversized body is rejected before opening its input stream. Unknown lengths are bounded while reading. These limits are client protections, not firmware recovery commands.
- Automatic thumbnail loading stops after the first connection failure, busy-server response or oversized preview. Folder enumeration stops after transport loss. Cached media stays visible and the camera status requests a manual refresh. Unsupported individual files can still be skipped without declaring the whole camera disconnected.
- Explicit Save uses a normal original-file GET and consumes it to completion. Account authentication can add its challenge/response request. Large originals do not inherit the short preview deadline. A save cancelled before it starts sends no request. Progress, cancellation, save receipts and SHA-256 calculation remain available.
- Connection logs identify the request kind and record received bytes and elapsed time. Credentials, authorization headers and arbitrary URL query values are excluded.

## Checks and remaining limits

Thirty Android workflow tests cover the photo/gallery features, a video viewer without playback controls or network access, video Save without autoplay, a camera fixture that becomes unusable after Range, stopping a failing thumbnail queue, and finishing a small response before another client starts. Seventy-one host checks cover protocols, links, original video/hash fidelity, cross-client serialization, queued cancellation, stalled cleanup, oversized responses, trickling-response deadlines and stopping a disconnected folder scan. The release also runs the APK inspection check.

These are simulated request and Android tests, not a physical-camera throughput measurement or a guarantee about camera firmware. An already-stuck camera may still need its connection restarted before the new app can use it.

Canon documents a Disconnect control on the CCAPI communication screen. If that screen responds, disconnect there and reconnect CCAPI before refreshing the app. If the camera itself no longer responds, a power cycle may still be necessary. Do not reset or format the card. See the [EOS R50 V CCAPI connection instructions](https://cam.start.canon/en/C021/manual/html/UG-07_Network_0070.html). Android's [HttpURLConnection reference](https://developer.android.com/reference/java/net/HttpURLConnection) describes the client connection API; it does not promise recovery of a remote camera service.
