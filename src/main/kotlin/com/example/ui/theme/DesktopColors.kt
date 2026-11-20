package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val DarkColorScheme =
  darkColorScheme(
    primary = AlterYellow,
    secondary = MutedText,
    tertiary = BorderGray,
    background = PitchBlack,
    surface = SlateGrayCard,
    surfaceVariant = SlateGrayCard,
    onSurfaceVariant = MutedText,
    onPrimary = PitchBlack,
    onSecondary = PitchBlack,
    primaryContainer = RaisedSurface,
    onPrimaryContainer = LightText,
    secondaryContainer = RaisedSurface,
    onSecondaryContainer = LightText,
    tertiaryContainer = RaisedSurface,
    onTertiaryContainer = LightText,
    onTertiary = LightText,
    onBackground = LightText,
    onSurface = LightText,
    outline = BorderGray,
    outlineVariant = BorderGray,
    error = ErrorRed,
    errorContainer = ErrorSurface,
    onError = PitchBlack,
    surfaceTint = PitchBlack,
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = SlateGrayCard,
    surfaceDim = PitchBlack,
    surfaceBright = RaisedSurface,
    surfaceContainer = SlateGrayCard,
    surfaceContainerHigh = RaisedSurface,
    surfaceContainerHighest = RaisedSurface
  )

internal val LightColorScheme =
  lightColorScheme(
    primary = AlterYellow,
    secondary = Color(0xFF666666),     // Slate-gray for subtext/secondary label
    tertiary = Color(0xFFEAEAEA),      // Light border/divider line gray
    background = Color(0xFFF7F8FA),    // Soft pristine off-white page background
    surface = Color(0xFFFFFFFF),       // Pure white for cards/surfaces
    surfaceVariant = Color(0xFFEAEAEA), // Grey for dialogs/popups instead of purple
    onPrimary = PitchBlack,
    onSecondary = Color(0xFF111111),   // Dark text on secondary
    onBackground = Color(0xFF111111),  // Dark text on background
    onSurface = Color(0xFF111111),     // Dark text on surface
    outline = Color.Black.copy(alpha = 0.08f),
    outlineVariant = Color(0xFFEAEAEA),
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = PitchBlack,
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = PitchBlack,
    tertiaryContainer = Color(0xFFEAEAEA),
    onTertiaryContainer = PitchBlack,
    onTertiary = PitchBlack,
    surfaceTint = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF666666),
    error = ErrorRed,
    onError = PitchBlack,
    errorContainer = Color(0xFFFFE4E4),
    onErrorContainer = PitchBlack,
  )
