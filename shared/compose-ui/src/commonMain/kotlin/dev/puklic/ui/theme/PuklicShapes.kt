package dev.puklic.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale per `docs/04_ui/design-system.md`. The custom [Circle] shape is
 * used for avatars and presence dots.
 */
public val PuklicShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Custom shape — full-circle, used for avatars + presence dots. */
public val Circle: Shape = CircleShape
