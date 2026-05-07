package com.little_star.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.detector.model.TaskType
import com.little_star.util.formatHintNoteRes
import com.little_star.util.formatHintSummaryRes
import com.little_star.util.formatHintTitleRes

/**
 * 模型管理格式说明弹窗
 * 供 HomeScreen 中的有模型/无模型两个界面复用
 *
 * @param taskType 当前任务类型（影响目录结构示例和说明文案）
 * @param onDismiss 关闭回调
 */
@Composable
fun FormatHintDialog(
    taskType: TaskType,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(taskType.formatHintTitleRes())) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 适用说明
                Text(
                    text = stringResource(taskType.formatHintSummaryRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 目录结构总览
                Text(stringResource(R.string.directory_structure), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${taskType.assetDir}/tflite/\n" +
                            "└─ ${stringResource(R.string.format_package_name)}/\n" +
                            "   ├─ label.txt\n" +
                            "   └─ models/\n" +
                            "     ├─ n/\n" +
                            "     │  ├─ model.tflite\n" +
                            "     │  └─ aot/${stringResource(R.string.format_optional)}\n" +
                            "     ├─ s/\n" +
                            "     ├─ m/\n" +
                            "     ├─ l/\n" +
                            "     └─ x/",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.format_task_types),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 导入方式一：目录
                Text(stringResource(R.string.method_directory), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.select_task_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stringResource(R.string.format_selected_dir)}\n" +
                            "└─ ${taskType.assetDir}/\n" +
                            "   └─ tflite/\n" +
                            "      └─ yolo26-custom/\n" +
                            "         ├─ label.txt\n" +
                            "         └─ models/\n" +
                            "           ├─ n/\n" +
                            "           │  ├─ model.tflite\n" +
                            "           │  └─ aot/${stringResource(R.string.format_optional)}\n" +
                            "           └─ x/model.tflite",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 导入方式二：压缩包
                Text(stringResource(R.string.method_archive), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.archive_structure_same),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "yolo26-custom.zip\n" +
                            "└─ ${taskType.assetDir}/\n" +
                            "   └─ tflite/\n" +
                            "      └─ yolo26-custom/\n" +
                            "         ├─ label.txt\n" +
                            "         └─ models/\n" +
                            "           ├─ n/\n" +
                            "           │  ├─ model.tflite\n" +
                            "           │  └─ aot/${stringResource(R.string.format_optional)}\n" +
                            "           └─ x/model.tflite",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 必需文件
                Text(stringResource(R.string.required_files), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(taskType.formatHintNoteRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 模型尺寸
                Text(stringResource(R.string.model_sizes_optional), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "n=Nano  s=Small  m=Medium  l=Large  x=XLarge",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // AOT 预编译
                Text(
                    stringResource(R.string.aot_precompiled),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.aot_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.aot_naming_rule),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
        }
    )
}

/**
 * 模型选择说明弹窗
 * 解释模型格式、包来源、尺寸选择等概念
 *
 * @param onDismiss 关闭回调
 */
@Composable
fun ModelSelectTipDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_selection_info)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.tflite_only),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.model_package_sources), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sources_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.model_size_info), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.size_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
        }
    )
}

/**
 * 推理配置说明弹窗
 * 解释推理模式、加速器兼容性、推理后端等概念
 *
 * @param onDismiss 关闭回调
 */
@Composable
fun InferenceConfigTipDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inference_config_info)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.inference_mode_label), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.inference_mode_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.accelerator_compatibility), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.accelerator_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.inference_backend_label), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.backend_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
        }
    )
}
