package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.margin
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.style.toModifier
import dev.kobwebssr.probe.CardStyle
import dev.kobwebssr.probe.MonoStyle
import dev.kobwebssr.probe.Page
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

@Page
@Composable
fun HomePage() {
    Page(
        title = "Kobweb, rendered on the server",
        lead = "A Kobweb site whose pages arrive already rendered — no fork of Kobweb, no fork of " +
            "Compose HTML, nothing patched upstream.",
    ) {
        Column(CardStyle.toModifier()) {
            Text("View the source of this page. The text you are reading is in it, and so are the CSS rules that style it. Switch JavaScript off and the site still works — that is the whole claim, and every page here is served that way.")
        }

        Column(Modifier.margin(top = 1.cssRem)) {
            Text("What it does")
        }
        Ul {
            Li { Text("Intercepts a page request before Kobweb's routing gets it, renders the page in a headless browser, and answers with the finished HTML.") }
            Li { Text("Discovers which routes exist from the metadata Kobweb's own KSP processor already writes.") }
            Li { Text("Carries the visitor's query string and language into the render, and keys its cache on them.") }
            Li { Text("Refuses to serve a page it cannot render faithfully, rather than serving the wrong one.") }
            Li { Text("Moves what the composition saved with rememberSaveable across to the client.") }
        }

        Column(Modifier.margin(top = 1.cssRem)) {
            Text("What it does not do")
        }
        Ul {
            Li { Text("Hydrate. The client still throws the server's tree away and rebuilds it — 11 nodes, 312 ms — and that cannot be fixed from outside Compose HTML.") }
            Li { Text("Give pages their own <title> or description. Kobweb has no mechanism for it, not even in its static export.") }
            Li { Text("Serve authenticated pages. They are refused on purpose until there is a design for them.") }
        }

        Column(CardStyle.toModifier().margin(top = 1.5.cssRem)) {
            Column(MonoStyle.toModifier()) {
                Text("RENDERED BY THE CLIENT — a marker the test suite looks for; ignore it")
            }
        }

        Column(Modifier.margin(top = 1.5.cssRem)) {
            A(href = "https://github.com/youndie/kobweb-ssr-experiment") { Text("Source and the full research log on GitHub") }
        }
    }
}
