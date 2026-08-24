package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.color
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.compose.ui.modifiers.fontSize
import com.varabyte.kobweb.compose.ui.modifiers.padding
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.style.CssStyle
import com.varabyte.kobweb.silk.style.base
import com.varabyte.kobweb.silk.style.toModifier
import org.jetbrains.compose.web.css.cssRem
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Text

/**
 * The page M1 renders on the server.
 *
 * The style is declared through Silk rather than as an inline attribute, and that is the point:
 * Silk builds its rules imperatively through the CSSOM, so a naive snapshot of this page carries
 * the class name and an empty `<style>` tag. Whether `.ssr-marker` shows up with its rules in the
 * response is what says the renderer did the CSSOM baking rather than just serialising the DOM.
 */
val SsrMarkerStyle = CssStyle.base {
    Modifier.color(Colors.ForestGreen).fontSize(1.5.cssRem).padding(16.px)
}

@Page
@Composable
fun SsrPage() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SSR PROBE PAGE")
        Column(SsrMarkerStyle.toModifier()) {
            Text("COMPOSED MARKER")
        }
    }
}
