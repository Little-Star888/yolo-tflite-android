package com.little_star.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.viewmodel.DetectorState

/**
 * 检测器状态提示卡片
 * 当模型未就绪时显示对应的提示信息，供各检测页面共用
 */
@Composable
fun DetectorStateBanner(
    detectorState: DetectorState,
    modifier: Modifier = Modifier
) {
    if (detectorState is DetectorState.Ready) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Text(
            text = when (detectorState) {
                is DetectorState.Idle -> stringResource(R.string.select_model_banner)
                is DetectorState.Loading -> stringResource(R.string.loading_model_banner)
                is DetectorState.Error -> stringResource(R.string.model_error_banner, detectorState.message)
                is DetectorState.Ready -> ""
            },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE65100)
        )
    }
}
