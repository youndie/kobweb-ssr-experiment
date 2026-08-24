package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.toAttrs
import com.varabyte.kobweb.compose.ui.modifiers.margin
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.style.toModifier
import dev.kobwebssr.probe.CardStyle
import dev.kobwebssr.probe.MonoStyle
import dev.kobwebssr.probe.Page
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

@Page
@Composable
fun HowPage() {
    Page(
        title = "How it works",
        lead = "Four pieces, none of them inside Kobweb.",
    ) {
        H2 { Text("1. An interceptor, not a route") }
        Column {
            Text("A KobwebServerPlugin gets the raw Ktor Application, so it can register anything. Registering a route is not enough: in the static layout an exported page owns the same path as a constant route, both are equally specific, and plugins are configured after Kobweb's own routing — so the page wins. An interceptor on the earliest pipeline phase wins in both layouts.")
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("2. A renderer in its own process") }
        Column {
            Text("The kobwebServerPlugin configuration is not transitive, so exactly one jar reaches the server — Playwright cannot ride along. The renderer is a sidecar reached over HTTP, which also makes the seam honest: swapping it for a Node process took no change to the interface.")
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("3. A bypass header") }
        Column {
            Text("The renderer produces the page by asking this same server for it. Without a marker to stand aside for, the interceptor would catch the renderer's own fetch and ask the renderer again, forever. The same recursion exists in threads: waiting for the renderer on a Ktor event-loop thread starves the loop the renderer needs, and eight concurrent requests all took exactly the client timeout until that was moved off.")
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("4. Baking the CSSOM into the markup") }
        Column {
            Text("Compose HTML creates empty style elements and fills them through the CSSOM, so a document saved as-is carries the tags and none of the rules. Each sheet's own rules are written back into its element before serialising — the same trick kobweb export uses, with one fix: iterate a snapshot of document.styleSheets, not the live collection.")
        }

        Column(CardStyle.toModifier().margin(top = 1.5.cssRem)) {
            Column(MonoStyle.toModifier()) {
                Text("visitor -> Ktor interceptor -> cache -> sidecar -> headless browser -> this same server")
            }
        }

        H2(attrs = Modifier.margin(top = 1.5.cssRem).toAttrs()) { Text("What each piece costs") }
        Ul {
            Li { Text("A render: about 0.63 s, measured on a laptop against an unminified development bundle.") }
            Li { Text("A cache hit: about 1.8 ms.") }
            Li { Text("A renderer worker: roughly 342 MiB, on top of about 115 MiB fixed. Pool size is bounded by memory, not by cores.") }
        }
    }
}
