package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import kotlin.js.Date

/**
 * The page M3-01 is measured on, served at `/state`.
 *
 * Note the file name: Kobweb derives the route from it, not from the function name. The first
 * version of this page was called SsrStatePage.kt and therefore answered at `/ssr-state-page`,
 * while the plugin was asking for `/ssr-state` — which the catch-all happily served as the home
 * page, rendered, 70131 bytes and no sign that anything was wrong.
 *
 * Both values are computed the same way; the only difference is `rememberSaveable` against
 * `remember`. [instanceId] is fixed per JavaScript instance, so the server's value and the
 * client's value are distinguishable on sight — which is the whole point. After a server render:
 *
 *  * `saveable` must still show the **server's** id, because the registry carried it across;
 *  * `plain` must show the **client's** id, because nothing carried it and it was recomputed.
 *
 * The second half is not a defect to be fixed later. Guaranteeing arbitrary state across the
 * boundary means serialising closures, which is where Qwik needs a compiler pass (research §1.3).
 */
private val instanceId = Date().getTime().toLong().toString().takeLast(6)

@Page
@Composable
fun StatePage() {
    val saveable = rememberSaveable { "saveable-from-$instanceId" }
    val plain = remember { "plain-from-$instanceId" }

    Column(Modifier.fillMaxSize()) {
        Div(attrs = { id("saveable") }) { Text(saveable) }
        Div(attrs = { id("plain") }) { Text(plain) }
        Div(attrs = { id("instance") }) { Text("rendered-by-$instanceId") }
    }
}
