package dev.kobwebssr.probe

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.style.CssStyle
import com.varabyte.kobweb.silk.style.base
import com.varabyte.kobweb.silk.style.toAttrs
import com.varabyte.kobweb.silk.style.toModifier
import com.varabyte.kobweb.compose.ui.toAttrs
import com.varabyte.kobweb.compose.css.TextDecorationLine
import com.varabyte.kobweb.compose.css.autoLength
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text

/**
 * The shell every demo page sits in.
 *
 * Deliberately built from Silk styles rather than inline attributes: Silk registers its rules
 * through the CSSOM, so a page styled this way is exactly the case a naive snapshot gets wrong.
 * If the site looks right with JavaScript switched off, the CSSOM baking did its job.
 */
val PageStyle = CssStyle.base {
    Modifier
        .fillMaxWidth()
        .maxWidth(52.cssRem)
        .margin(leftRight = autoLength)
        .padding(topBottom = 2.cssRem, leftRight = 1.5.cssRem)
        .fontFamily("system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif")
        .lineHeight(1.6)
}

val NavStyle = CssStyle.base {
    Modifier
        .fillMaxWidth()
        .padding(bottom = 1.cssRem)
        .borderBottom(1.px, LineStyle.Solid, Colors.Gainsboro)
        .margin(bottom = 2.cssRem)
        .gap(1.cssRem)
        .flexWrap(FlexWrap.Wrap)
}

val NavLinkStyle = CssStyle.base {
    Modifier.color(Colors.SlateGray).textDecorationLine(TextDecorationLine.None).fontSize(0.95.cssRem)
}

val LeadStyle = CssStyle.base {
    Modifier.fontSize(1.15.cssRem).color(Colors.DimGray).margin(bottom = 1.5.cssRem)
}

val CardStyle = CssStyle.base {
    Modifier
        .fillMaxWidth()
        .padding(1.cssRem)
        .backgroundColor(Colors.WhiteSmoke)
        .borderRadius(8.px)
        .margin(bottom = 1.cssRem)
}

val MonoStyle = CssStyle.base {
    Modifier
        .fontFamily("ui-monospace", "SFMono-Regular", "Menlo", "monospace")
        .fontSize(0.9.cssRem)
        .color(Colors.DarkSlateGray)
}

private val pages = listOf(
    "/" to "Overview",
    "/how" to "How it works",
    "/findings" to "Findings",
    "/echo" to "Request context",
    "/state" to "State transfer",
)

@Composable
fun Page(title: String, lead: String, content: @Composable () -> Unit) {
    Column(PageStyle.toModifier()) {
        Row(NavStyle.toModifier(), verticalAlignment = Alignment.CenterVertically) {
            pages.forEach { (href, label) ->
                A(href = href, attrs = NavLinkStyle.toAttrs()) { Text(label) }
            }
        }
        H1(attrs = Modifier.fontSize(2.cssRem).margin(bottom = 0.5.cssRem).toAttrs()) { Text(title) }
        Column(LeadStyle.toModifier()) { Text(lead) }
        content()
    }
}
