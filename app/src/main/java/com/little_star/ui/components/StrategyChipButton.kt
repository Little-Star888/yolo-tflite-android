package com.little_star.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.little_star.R
import com.little_star.pipeline.PipelineStrategy

@Composable
fun StrategyChipButton(
    effectiveStrategy: PipelineStrategy,
    strategyChain: List<PipelineStrategy>,
    availableStrategies: List<PipelineStrategy>,
    isAutoMode: Boolean,
    onSelectStrategy: (PipelineStrategy) -> Unit,
    onSelectAuto: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val autoSelectLabel = stringResource(R.string.strategy_auto_select)
    val notImplementedLabel = stringResource(R.string.strategy_not_implemented)
    val unavailableLabel = stringResource(R.string.strategy_unavailable)
    val effectiveName = stringResource(effectiveStrategy.displayNameRes)

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xCC1A1A2E),
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = strategyIcon(effectiveStrategy),
                        contentDescription = effectiveName,
                        tint = strategyColor(effectiveStrategy),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isAutoMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(8.dp)
                        .background(Color(0xFF4FC3F7), CircleShape)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xE61A1A2E)
        ) {
            val autoLabel = autoSelectLabel +
                if (isAutoMode) " · $effectiveName" else ""
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "◎",
                            color = if (isAutoMode) Color(0xFF4FC3F7) else Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = autoLabel,
                            color = if (isAutoMode) Color(0xFF4FC3F7) else Color.White,
                            fontSize = 13.sp
                        )
                        if (isAutoMode) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF4FC3F7),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                onClick = {
                    expanded = false
                    onSelectAuto()
                }
            )

            HorizontalDivider(color = Color(0x33FFFFFF))

            strategyChain.forEach { strategy ->
                val isAvailable = strategy in availableStrategies
                val isSelected = !isAutoMode && strategy == effectiveStrategy

                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = strategyIcon(strategy),
                                contentDescription = null,
                                tint = when {
                                    !isAvailable -> Color(0xFF444444)
                                    isSelected -> strategyColor(strategy)
                                    else -> strategyColor(strategy).copy(alpha = 0.7f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(strategy.displayNameRes),
                                    color = when {
                                        isSelected -> strategyColor(strategy)
                                        isAvailable -> Color.White
                                        else -> Color(0xFF666666)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (!isAvailable) {
                                        if (strategy.isZeroCopy) notImplementedLabel else unavailableLabel
                                    } else stringResource(strategy.subtextRes),
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = strategyColor(strategy),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (isAvailable) {
                            onSelectStrategy(strategy)
                        }
                    },
                    enabled = isAvailable
                )
            }
        }
    }
}

private fun strategyIcon(strategy: PipelineStrategy): ImageVector = when (strategy) {
    PipelineStrategy.GL_ZEROCOPY -> Icons.Filled.Bolt
    PipelineStrategy.GL_TRANSIT -> Icons.Filled.Bolt
    PipelineStrategy.CPU_PIPELINE -> Icons.Outlined.Bolt
}

private fun strategyColor(strategy: PipelineStrategy): Color = when (strategy) {
    PipelineStrategy.GL_ZEROCOPY -> Color(0xFFFFC107)
    PipelineStrategy.GL_TRANSIT -> Color(0xFF4FC3F7)
    PipelineStrategy.CPU_PIPELINE -> Color(0xFF888888)
}
