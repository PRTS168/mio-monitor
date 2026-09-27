package com.a41probe.monitor.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.ui.theme.Ink

/**
 * 页面背景立绘：澪固定立于内容区右下（透明底 PNG，直接融入页面底色），
 * 数据卡片（雾玻璃）浮在其上滚动，透出角色。art==0 时不绘制。
 * tab 切换时立绘 220ms 交叉淡入，与页面转场节奏一致。
 * v0.26.6: 透明度已调回（用户确认曲线问题为数据不同源，非立绘遮挡）。
 * 曲线可读性改由曲线卡内嵌不透明底（NestedTile）保障。
 */
@Composable
fun MioArtBackground(art: Int, modifier: Modifier = Modifier) {
    if (art == 0) return
    AnimatedContent(
        targetState = art,
        transitionSpec = {
            fadeIn(tween(220)).togetherWith(fadeOut(tween(220)))
        },
        label = "mioArt",
    ) { a ->
        Box(modifier.fillMaxSize()) {
            Image(
                painter = painterResource(a),
                contentDescription = null,
                contentScale = ContentScale.FillHeight,
                // v0.28.3: 离屏缓存——立绘成为固定位图，滚动时 GPU 直接合成缓存
                // 层，不再每帧重新绘制 420dp 全幅透明 PNG（滑动卡顿根因之一）
                modifier = Modifier.align(Alignment.BottomEnd).height(420.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            )
        }
    }
}

/**
 * 页面大标题（无卡片背景，iOS 大标题风格）：置于 LazyColumn 首个 item。
 * 与 MioArtBackground 搭配——立绘在背景层，标题在内容流顶部。
 */
@Composable
fun MioPageTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(title, color = Ink.tx, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Ink.tx3, fontSize = 12.sp)
    }
}
