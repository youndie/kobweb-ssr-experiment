package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import org.jetbrains.compose.web.dom.Text

/**
 * The subject of M0-03: a real Kobweb page whose route a server plugin will also claim. Whichever
 * side answers a request for "/collide" is the answer the milestone is after.
 */
@Page
@Composable
fun CollidePage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("RENDERED BY THE CLIENT")
    }
}
