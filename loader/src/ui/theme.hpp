#pragma once
#include "core/color.hpp"

namespace mindless::ui
{

struct Theme
{
    Color background = 0x090B0C;
    Color surface = 0x0D1012;
    Color surfaceRaised = 0x181B1C;
    Color surfaceBorder = 0x2B2D2D;
    Color divider = 0x292B2B;
    Color text = 0xEBEAE6;
    Color textSecond = 0x9D9E9C;
    Color textDisable = 0x696C6C;
    Color accent = 0xEBEAE6;
    Color accentHover = 0xFFFFFF;
    Color accentPress = 0xC9C9C5;
    Color accentDim = Color(0xEBEAE6).with_alpha(0.14f);
    Color accentGlow = 0xFFFFFF;
    Color accentText = 0x111314;
    Color buttonBg = 0x181B1C;
    Color buttonHover = 0x1F2223;
    Color buttonPress = 0x121415;
    Color buttonBorder = 0x303333;
    Color success = 0xD8D8D4;
    Color warning = 0xB8B8B4;
    Color danger = 0xF0F0EC;
    Color trackBg = 0x202324;
    Color trackFill = 0xEBEAE6;
    Color selectionBg = Color(0xEBEAE6).with_alpha(0.10f);
    Color selectionRing = Color(0xEBEAE6).with_alpha(0.34f);
    Color glowColor = Color(0x000000).with_alpha(0.56f);
    float glowSpread = 28.0f;
    float glowMargin = 34.0f;
    float windowRadius = 8.0f;
    float cardRadius = 6.0f;
    float buttonRadius = 5.0f;
    float tagRadius = 4.0f;
    float borderWidth = 1.0f;
    float windowPadding = 24.0f;
    float itemSpacing = 8.0f;
    float sectionGap = 18.0f;
    float buttonH = 36.0f;
    float progressH = 3.0f;
    float fontSizeNormal = 13.0f;
    float fontSizeTitle = 18.0f;
    float fontSizeSmall = 11.0f;
};

extern Theme g_theme;

}
