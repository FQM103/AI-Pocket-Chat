package com.situ.aichat.ui.pet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.gift.GiftColors

// 宠物弹窗组（从 PetDetailScreen 抽出·只搬不改）：改名 AppDialog（点宠物名打开）+
// 散步结算 AppSheet（散步回来弹·事件描述 + 纪念品 + 心情/成长/金币三奖励 RewardItem）。
@Composable
internal fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current)) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = "给宠物改名",
        confirmText = "确定",
        onConfirm = { onConfirm(value.text) },
        confirmEnabled = value.text.trim().isNotEmpty(),
        dismissText = "取消",
        onDismiss = onDismiss,
        content = { AppTextField(value = value, onValueChange = { value = it }, singleLine = true, label = "宠物名字") },
    )
}

// pet-ui-5：散步结算改 ModalBottomSheet（= iOS PetWalkSettlementView .sheet/.medium，项目既定 .medium→ModalBottomSheet 映射）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalkSettlementDialog(petName: String, settlement: PetWalkService.WalkSettlement, onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp), // = iOS Spacing.xl
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsWalk, // = iOS figure.walk 48pt
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text("$petName 散步回来了！", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) // = iOS title3 bold
            Text(
                settlement.eventDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            settlement.souvenir?.let { s ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), // ≈ iOS themeSurfaceCard
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(s.emoji, fontSize = 56.sp) // = iOS 56pt
                        Text("获得了：${s.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // M1：散步奖励图标换暖——心情=莫兰迪暖玫(pet.mood)·成长=陶土(accent.text)·替原粉#EC407A/紫#AB47BC；金币留金。
                RewardItem(Icons.Filled.Favorite, settlement.moodBonus, AppTheme.colors.pet.mood, "心情")
                RewardItem(Icons.Filled.ArrowCircleUp, settlement.growthBonus, AppTheme.colors.accent.text, "成长")
                RewardItem(Icons.Filled.MonetizationOn, settlement.coinsReward, GiftColors.Gold, "金币")
            }
            AppButton(onClick = onDismiss, style = AppButtonStyle.Text) { Text("好的") }
        }
    }
}

@Composable
private fun RewardItem(icon: ImageVector, value: Int, color: Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        // 奖励皆非负，"+" 字面 = iOS .sign(.always)
        Text("+$value", fontWeight = FontWeight.Bold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
