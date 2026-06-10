package com.garfiec.librechat.core.ui.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * The key [UIWindow] of the foreground-active window scene.
 *
 * `connectedScenes` is an unordered set, so the foreground-active scene is selected explicitly —
 * an arbitrary first element can be a background scene (notably in multi-window / iPad), and
 * anchoring UI to it silently fails.
 *
 * Use this when you need a window (e.g. an `ASPresentationAnchor`); use
 * [currentTopmostViewController] when you need a controller to present from.
 */
fun currentKeyWindow(): UIWindow? {
    val windowScenes = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
    val scene = windowScenes.firstOrNull {
        it.activationState == UISceneActivationStateForegroundActive
    } ?: windowScenes.firstOrNull()
    return scene?.keyWindow
}

/**
 * The view controller a modal / share sheet should be presented from: the topmost presented
 * controller of the foreground-active scene's key window.
 *
 * Walking `presentedViewController` to the top matters because callers are often themselves
 * presented modally — presenting on the bare root would throw "already presenting".
 *
 * Shared by the media viewer's share, artifact share, and every iOS file picker
 * (document / photo / camera / agent / skill) so scene + modal selection lives in one place.
 */
fun currentTopmostViewController(): UIViewController? {
    val root = currentKeyWindow()?.rootViewController ?: return null
    var top = root
    while (top.presentedViewController != null) {
        top = top.presentedViewController!!
    }
    return top
}

/**
 * Presents [sheet] (typically a `UIActivityViewController` share sheet) modally from the
 * [from] controller, anchoring its popover to that controller's view center.
 *
 * iPad presents these as popovers and throws if they have no anchor, so every share-sheet call
 * site routes through here instead of repeating the anchor dance. Callers that need to fail fast
 * when no controller exists should resolve [from] via [currentTopmostViewController] themselves and
 * guard on null before doing work; this helper just anchors and presents.
 */
@OptIn(ExperimentalForeignApi::class)
fun presentSheet(sheet: UIViewController, from: UIViewController) {
    sheet.popoverPresentationController?.let { popover ->
        val view = from.view
        popover.sourceView = view
        view?.bounds?.useContents {
            popover.sourceRect = CGRectMake(
                x = size.width / 2.0,
                y = size.height / 2.0,
                width = 0.0,
                height = 0.0,
            )
        }
    }
    from.presentViewController(sheet, animated = true, completion = null)
}
