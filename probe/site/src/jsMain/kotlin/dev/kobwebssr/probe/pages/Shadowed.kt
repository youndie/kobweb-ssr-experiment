package dev.kobwebssr.probe.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import org.jetbrains.compose.web.dom.Text

/**
 * The M5-05 fixture: a real page whose route `conf.yaml` also declares as a redirect source.
 *
 * It exists as its own page rather than reusing `/echo`, which was the first attempt and promptly
 * broke every M5-02 assertion — `/echo` stopped rendering and started answering 301, so the checks
 * that read its output found nothing. A fixture that demonstrates one property must not quietly
 * take away another fixture's.
 */
@Page
@Composable
fun ShadowedPage() {
    Column(Modifier.fillMaxSize()) {
        Text("RENDERED BY THE CLIENT")
    }
}
