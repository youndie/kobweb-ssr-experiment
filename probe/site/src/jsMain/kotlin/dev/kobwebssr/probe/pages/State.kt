package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.margin
import com.varabyte.kobweb.compose.ui.toAttrs
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.style.toModifier
import dev.kobwebssr.probe.CardStyle
import dev.kobwebssr.probe.MonoStyle
import dev.kobwebssr.probe.Page
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import kotlin.js.Date

/**
 * The page state transfer is measured on, served at `/state`.
 *
 * Both values are computed the same way; the only difference is `rememberSaveable` against
 * `remember`. [instanceId] is fixed per JavaScript instance, so the server's value and the client's
 * are distinguishable on sight — which is the whole point. After a server render the saveable one
 * still shows the *server's* id because the registry carried it across, and the plain one shows the
 * *client's* because nothing carried it and it was recomputed.
 *
 * The second half is not a defect to be fixed later. Guaranteeing arbitrary state across the
 * boundary means serialising closures, which is where Qwik needs a compiler pass.
 *
 * Note the file name: Kobweb derives the route from it, not from the function name. The first
 * version of this page was called SsrStatePage.kt and therefore answered at `/ssr-state-page`,
 * while the plugin was asking for `/ssr-state` — which the catch-all happily served as the home
 * page, rendered, 70131 bytes and no sign that anything was wrong.
 */
private val instanceId = Date().getTime().toLong().toString().takeLast(6)

@Page
@Composable
fun StatePage() {
    val saveable = rememberSaveable { "saveable-from-$instanceId" }
    val plain = remember { "plain-from-$instanceId" }

    Page(
        title = "State transfer",
        lead = "Two values, computed identically. One was declared saveable and crossed from the " +
            "server; the other was not and was recomputed here.",
    ) {
        Column(CardStyle.toModifier()) {
            Column(MonoStyle.toModifier()) {
                Div(attrs = { id("saveable") }) { Text(saveable) }
                Div(attrs = { id("plain") }) { Text(plain) }
                Div(attrs = { id("instance") }) { Text("rendered-by-$instanceId") }
            }
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("Read the three numbers") }
        Column {
            Text("The last line is this browser's instance. If the first line differs from it, that value was produced on the server and travelled here inside the document. The middle line always matches the last, because a plain remember has nothing carrying it.")
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("Built on Compose, not beside it") }
        Column {
            Text("This uses Compose's own SaveableStateRegistry rather than a bespoke serialiser. It brings the right discipline with it: state crosses the boundary when its author wrote rememberSaveable, and only then. Everything else is recomputed, which is the documented behaviour rather than a gap.")
        }
    }
}
