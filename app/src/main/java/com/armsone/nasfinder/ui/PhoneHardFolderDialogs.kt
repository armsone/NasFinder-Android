package com.armsone.nasfinder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsone.nasfinder.data.PhoneHardItem
import com.armsone.nasfinder.data.PhoneHardTarget

/**
 * Dialog prompting for a new folder name and invoking creation.
 */
@Composable
fun CreateFolderDialog(
    parentPath: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentPath.isNullOrBlank()) "새 폴더 만들기" else "'${parentPath.substringAfterLast('/')}'에 새 폴더") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorText = null
                    },
                    label = { Text("폴더 이름") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isBlank() || trimmed == "." || trimmed == ".." || trimmed.contains('/') || trimmed.contains('\\')) {
                        errorText = "올바른 폴더 이름을 입력하세요."
                    } else {
                        onConfirm(trimmed)
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("만들기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

/**
 * Dialog for selecting a destination folder in PhoneHard for Move or Copy operations.
 */
@Composable
fun SelectPhoneHardDestinationDialog(
    title: String,
    model: NasFinderViewModel,
    targets: List<PhoneHardTarget>,
    onDismiss: () -> Unit,
    onSelectDestination: (String?) -> Unit,
) {
    var currentSubpath by remember { mutableStateOf<String?>(null) }
    val entries = remember(currentSubpath) { model.loadPhoneHardFolder(currentSubpath) }
    val folderEntries = remember(entries) {
        entries.filter { it.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY }
    }

    val disabledRelativePaths = remember(targets) {
        targets.filter { it.isDirectory }.map { it.relativePath.trim().removePrefix("/").removeSuffix("/") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Breadcrumb / current location bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (currentSubpath.isNullOrBlank()) "폰하드 (최상위)" else "폰하드 > ${currentSubpath!!.replace('/', ' ')}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!currentSubpath.isNullOrBlank()) {
                        TextButton(onClick = {
                            val parent = currentSubpath!!.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                            currentSubpath = parent
                        }) {
                            Text("상위로")
                        }
                    }
                }

                // Folder list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (folderEntries.isEmpty()) {
                        item {
                            Text(
                                "하위 폴더가 없습니다. 현재 폴더를 선택할 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    } else {
                        items(folderEntries, key = { it.relativePath }) { folder ->
                            val normRel = folder.relativePath.trim().removePrefix("/").removeSuffix("/")
                            val isDisabled = disabledRelativePaths.any { normRel == it || normRel.startsWith("$it/") }

                            Surface(
                                onClick = { if (!isDisabled) currentSubpath = folder.relativePath },
                                enabled = !isDisabled,
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDisabled) 0.38f else 1f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        null,
                                        tint = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        folder.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isDisabled) {
                                        Text(
                                            "선택 불가",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelectDestination(currentSubpath) }) {
                Text(if (currentSubpath.isNullOrBlank()) "최상위로 이동/복사" else "현재 폴더로 선택")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
