package `in`.aniscroll.tv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Wraps a [FocusRequester] and tracks its live `Modifier.Node` attachment count.
 *
 * Calling [orDefault] inside `Modifier.focusProperties { ... }` guarantees that directional
 * focus overrides (`up`, `down`, `left`, `right`) never hand Compose an unattached
 * [FocusRequester] during lazy list recycling, horizontal scrolling, or visibility transitions,
 * preventing `IllegalStateException: FocusRequester is not initialized`.
 */
class SafeFocusRequester(
    val focusRequester: FocusRequester = FocusRequester()
) {
    private var attachmentCount by mutableIntStateOf(0)

    val isAttached: Boolean
        get() = attachmentCount > 0

    internal fun incrementAttachment() {
        attachmentCount += 1
    }

    internal fun decrementAttachment() {
        if (attachmentCount > 0) {
            attachmentCount -= 1
        }
    }

    fun orDefault(): FocusRequester = if (isAttached) focusRequester else FocusRequester.Default

    fun requestFocusSafely(): Boolean {
        if (!isAttached) return false
        return runCatching {
            focusRequester.requestFocus()
            true
        }.getOrDefault(false)
    }
}

@Composable
fun rememberSafeFocusRequester(
    backingRequester: FocusRequester? = null
): SafeFocusRequester = remember(backingRequester) {
    SafeFocusRequester(backingRequester ?: FocusRequester())
}

fun Modifier.safeFocusRequester(safe: SafeFocusRequester): Modifier =
    this
        .focusRequester(safe.focusRequester)
        .then(SafeFocusAttachmentElement(safe))

fun Modifier.trackFocusAttachment(safe: SafeFocusRequester): Modifier =
    this.then(SafeFocusAttachmentElement(safe))

private data class SafeFocusAttachmentElement(
    val safe: SafeFocusRequester
) : ModifierNodeElement<SafeFocusAttachmentNode>() {
    override fun create(): SafeFocusAttachmentNode = SafeFocusAttachmentNode(safe)

    override fun update(node: SafeFocusAttachmentNode) {
        node.updateSafe(safe)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "safeFocusAttachment"
    }
}

private class SafeFocusAttachmentNode(
    private var safe: SafeFocusRequester
) : Modifier.Node() {
    private var isCountedAttached = false

    override fun onAttach() {
        if (!isCountedAttached) {
            safe.incrementAttachment()
            isCountedAttached = true
        }
    }

    override fun onReset() {
        if (isCountedAttached) {
            safe.decrementAttachment()
            isCountedAttached = false
        }
    }

    override fun onDetach() {
        if (isCountedAttached) {
            safe.decrementAttachment()
            isCountedAttached = false
        }
    }

    fun updateSafe(newSafe: SafeFocusRequester) {
        if (safe !== newSafe) {
            if (isCountedAttached) {
                safe.decrementAttachment()
                isCountedAttached = false
            }
            safe = newSafe
            if (isAttached) {
                safe.incrementAttachment()
                isCountedAttached = true
            }
        }
    }
}
