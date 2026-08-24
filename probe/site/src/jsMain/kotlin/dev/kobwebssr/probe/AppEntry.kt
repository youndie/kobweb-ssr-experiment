package dev.kobwebssr.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxHeight
import com.varabyte.kobweb.core.App
import com.varabyte.kobweb.silk.SilkApp
import com.varabyte.kobweb.silk.components.layout.Surface
import com.varabyte.kobweb.silk.init.InitSilk
import com.varabyte.kobweb.silk.init.InitSilkContext
import com.varabyte.kobweb.silk.init.registerStyleBase
import com.varabyte.kobweb.silk.style.common.SmoothColorStyle
import com.varabyte.kobweb.silk.style.toModifier

@InitSilk
fun initStyles(ctx: InitSilkContext) {
    ctx.stylesheet.registerStyleBase("html, body") { Modifier.fillMaxHeight() }
}

@App
@Composable
fun AppEntry(content: @Composable () -> Unit) {
    // The registry is created once per composition and exposed to the renderer immediately. It has
    // to wrap everything, because a `rememberSaveable` anywhere below needs to find it.
    val registry = remember {
        SsrState.createRegistry().also { SsrState.exposeTo(it) }
    }
    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        SilkApp {
            Surface(SmoothColorStyle.toModifier().fillMaxHeight()) {
                content()
            }
        }
    }
}
