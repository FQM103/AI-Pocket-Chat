package com.situ.aichat.ui.world.resident

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R

/**
 * 出生地选择 sheet（战役 B·图纸 §4.2·结构照 CharacterWorldSection.WorldCityPickerSheet 同款·数据 = [ResidentFormState]
 * 里 WorldAtlas 现算的 regions/citiesOfRegion）：与主表单同暗面（[ResRaised]）。大区 chips 横滚 + 城市列表·选中即回填关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResidentCityPickerSheet(
    state: ResidentFormState,
    onSelectRegion: (String) -> Unit,
    onSelectCity: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ResRaised) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(
                stringResource(R.string.world_resident_city_sheet_title),
                fontSize = 16.sp, color = ResText1, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.world_resident_city_sheet_sub),
                fontSize = 12.sp, color = ResText2,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            // 大区 chips（横滚）。
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.regions.forEach { region ->
                    ResidentChip(region.name, selected = region.id == state.selectedRegionId) { onSelectRegion(region.id) }
                }
            }
            // 城市列表（当前大区过滤）。
            state.citiesOfRegion.forEach { city ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelectCity(city.id, city.name) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(city.name, fontSize = 15.sp, color = ResText1, modifier = Modifier.weight(1f))
                    if (city.id == state.cityId) Text("✓", fontSize = 15.sp, color = ResGold)
                }
            }
        }
    }
}
