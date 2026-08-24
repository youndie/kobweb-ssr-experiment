package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import org.jetbrains.compose.web.dom.Text

/**
 * The subject of M0-04: same collision as [CollidePage], but the plugin claims this route from an
 * early pipeline interceptor instead of from `routing { }`.
 */
@Page
@Composable
fun Collide2Page() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("RENDERED BY THE CLIENT")
    }
}
