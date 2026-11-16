package androidx.media3.session

import androidx.media3.common.util.UnstableApi

/**
 * Test-only access to Media3's controller-request scope.
 *
 * `MediaSession.getControllerForCurrentRequest()` is non-null only for
 * the duration of a controller-originated `Player` call, set by Media3
 * via the package-private `MediaSessionImpl.callWithControllerForCurrentRequestSet`.
 * That setter is the same mechanism the production session uses to mark
 * a call as controller-originated, so driving a block through it gives
 * a faithful "this is a controller request" window for tests of
 * [com.margelo.nitro.queueplayer.SessionCommandForwardingPlayer], whose
 * interception gates on exactly that signal.
 *
 * Declared in `androidx.media3.session` so it can reach the
 * package-private `MediaSession.getImpl()` accessor; this is a test
 * construct under `src/test` and adds no production surface.
 */
@UnstableApi
fun MediaSession.runWithControllerForCurrentRequest(
  controller: MediaSession.ControllerInfo,
  block: () -> Unit,
) {
  getImpl().callWithControllerForCurrentRequestSet(controller) { block() }.run()
}
