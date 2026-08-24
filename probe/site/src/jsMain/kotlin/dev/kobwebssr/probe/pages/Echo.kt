package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * The page M5-02 is measured on: it renders things that only the *request* can supply.
 *
 * Before M5-02 both of these were constants no matter what the visitor asked for — the renderer
 * built its own URL and its own browser context, so `q` was always absent and the language was
 * always the renderer's. Which is exactly why the page has to exist: a page that ignores the
 * request cannot tell a working implementation from a broken one.
 */
@Page
@Composable
fun EchoPage() {
    val ctx = rememberPageContext()
    Column(Modifier.fillMaxSize()) {
        Div(attrs = { id("q") }) { Text("q=" + (ctx.route.queryParams["q"] ?: "<absent>")) }
        Div(attrs = { id("lang") }) { Text("lang=" + window.navigator.language) }
    }
}
