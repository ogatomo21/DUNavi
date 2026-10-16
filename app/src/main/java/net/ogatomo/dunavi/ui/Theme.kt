package net.ogatomo.dunavi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun DUNaviTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xffb8c6f2), onPrimary = Color(0xff23345f),
            primaryContainer = Color(0xff394a76), onPrimaryContainer = Color(0xffdce2ff),
            secondary = Color(0xffbcc7e5), onSecondary = Color(0xff263149),
            secondaryContainer = Color(0xff3c475f), onSecondaryContainer = Color(0xffd9e2ff),
            tertiary = Color(0xffa4d0bc), onTertiary = Color(0xff0a3828),
            tertiaryContainer = Color(0xff254f3e), onTertiaryContainer = Color(0xffbfecd7),
            background = Color(0xff111318), onBackground = Color(0xffe2e2e9),
            surface = Color(0xff111318), onSurface = Color(0xffe2e2e9),
            surfaceContainerLowest = Color(0xff0c0e13), surfaceContainerLow = Color(0xff191c21),
            surfaceContainer = Color(0xff1e2026), surfaceContainerHigh = Color(0xff282a30),
            surfaceContainerHighest = Color(0xff33353b),
        )
    } else {
        lightColorScheme(
            primary = Color(0xff435b91), onPrimary = Color.White,
            primaryContainer = Color(0xffd9e2ff), onPrimaryContainer = Color(0xff122c5e),
            secondary = Color(0xff565f75), onSecondary = Color.White,
            secondaryContainer = Color(0xffdae2f9), onSecondaryContainer = Color(0xff141c2e),
            tertiary = Color(0xff3c6653), onTertiary = Color.White,
            tertiaryContainer = Color(0xffbfecd7), onTertiaryContainer = Color(0xff002115),
            background = Color(0xfff9f9ff), onBackground = Color(0xff1a1b20),
            surface = Color(0xfff9f9ff), onSurface = Color(0xff1a1b20),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff2f3fc),
            surfaceContainer = Color(0xffeceef6), surfaceContainerHigh = Color(0xffe6e8f0),
            surfaceContainerHighest = Color(0xffe1e2ea),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
