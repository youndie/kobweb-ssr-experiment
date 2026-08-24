package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.margin
import com.varabyte.kobweb.compose.ui.toAttrs
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.silk.style.toModifier
import dev.kobwebssr.probe.CardStyle
import dev.kobwebssr.probe.MonoStyle
import dev.kobwebssr.probe.Page
import kotlinx.browser.window
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text

/**
 * Renders things only the *request* can supply, served at `/echo`.
 *
 * Before the request reached the render both of these were constants no matter what the visitor
 * asked for: the renderer built its own URL and its own browser context. Which is why the page has
 * to exist — a page that ignores the request cannot tell a working implementation from a broken one.
 */
@Page
@Composable
fun EchoPage() {
    val ctx = rememberPageContext()
    Page(
        title = "Request context",
        lead = "Both values below come from your request, and both are produced on the server. " +
            "Change the query string or your browser's language and reload.",
    ) {
        Column(CardStyle.toModifier()) {
            Column(MonoStyle.toModifier()) {
                Div(attrs = { id("q") }) { Text("q=" + (ctx.route.queryParams["q"] ?: "<absent>")) }
                Div(attrs = { id("lang") }) { Text("lang=" + window.navigator.language) }
            }
        }

        Column(Modifier.margin(top = 1.cssRem)) {
            A(href = "/echo?q=hello") { Text("Try it with ?q=hello") }
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("Why this is not free") }
        Column {
            Text("Everything the render depends on has to be in the cache key too, or the first visitor's page is served to the next one. Cookies and credentials are deliberately not carried at all: a personal render is either uncached, at 0.6 s per request per visitor, or cached per user, where one wrong key hands someone another person's page. Requests carrying them are refused rather than answered wrongly.")
        }
    }
}
