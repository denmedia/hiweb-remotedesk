package com.hiweb.hiwebremotedesk.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Граница «телефон / планшет» по ширине окна в dp (как WindowSizeClass.Compact/Medium).
 * Телефон SM-S916B: 384 dp в книжной ориентации и 854 dp в альбомной — вёрстка должна
 * работать в обеих, а планшет Tab S9 Ultra (≈ 640 dp в книжной) остаётся на широкой раскладке.
 */
val CompactMaxWidth: Dp = 600.dp
