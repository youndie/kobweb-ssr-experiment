package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.toAttrs
import com.varabyte.kobweb.compose.ui.modifiers.margin
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.style.toModifier
import dev.kobwebssr.probe.CardStyle
import dev.kobwebssr.probe.Page
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text

@Composable
private fun Finding(title: String, body: String) {
    Column(CardStyle.toModifier()) {
        H2(attrs = Modifier.margin(bottom = 0.5.cssRem).toAttrs()) { Text(title) }
        Text(body)
    }
}

@Page
@Composable
fun FindingsPage() {
    Page(
        title = "Findings",
        lead = "The ones that changed what got built. The full log, including the measurements " +
            "and the corrections, is in the repository.",
    ) {
        Finding(
            "A custom Applier does not get you hydration",
            "Compose HTML's Applier is reachable — it is opt-in, not internal. It is also useless for this: " +
                "attributes, inline styles, classes and listeners are written straight into the browser node inside " +
                "TagElement's update block and never pass through the Applier. A custom one sees insert, remove and " +
                "move, and nothing else.",
        )
        Finding(
            "And nothing can adopt an existing node",
            "DomNodeWrapper.insert only ever inserts or appends, and the element factory always makes a fresh node. " +
                "A composition cannot take over markup that is already in the document, so hydration is not something " +
                "a plugin can add. Measured cost of the alternative: 11 nodes removed, the first 312 ms after the " +
                "head is parsed, replaced by markup identical to what they were.",
        )
        Finding(
            "A JVM target for Compose HTML is not close",
            "There is no YouTrack issue for one. The nearest neighbour — wasmJs support, strictly the easier job — " +
                "has been open and unassigned since March 2024. Meanwhile html-core-jvm is already published and is " +
                "an empty jar, so a JVM source set depending on it resolves and gives you nothing.",
        )
        Finding(
            "A renderer reports success on an error page",
            "A browser draws an error page as willingly as a real one, so a snapshot of a 500 comes back as valid " +
                "HTML with status 200. The result is checked by content instead: a Kobweb root that exists and is " +
                "not empty, and no style element left blank.",
        )
        Finding(
            "The number a decision rested on measured my own code",
            "Rendering in Node under jsdom was rejected for losing 61 CSS rules — a third of the stylesheet. It was " +
                "not losing any of them. The baking script was iterating a live collection by index while assigning " +
                "to it. The decision survived; its reason did not.",
        )
    }
}
