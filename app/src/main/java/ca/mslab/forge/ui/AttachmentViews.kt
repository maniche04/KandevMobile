package ca.mslab.forge.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import ca.mslab.forge.ui.theme.ForgeTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ca.mslab.forge.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AttachmentComposer(model: ConnectionViewModel) {
    val controller = model.attachments
    if (controller.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
    controller.error?.let { ErrorNotice(it) }
    if (controller.files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        controller.files.forEach { local ->
            OutlinedCard(onClick = { controller.previewLocal(local) }, shape = MaterialTheme.shapes.small) {
                Row(Modifier.widthIn(max = 260.dp).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(attachmentIcon(local.mimeType), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(local.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${attachmentSize(local.size)} · " + when {
                            controller.uploading == local.id -> "Uploading…"
                            local.uploaded?.expired() == true -> "Upload expired · check delivery"
                            local.uploaded != null -> "Uploaded"
                            else -> "On device"
                        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (local.kind == "image") IconButton(onClick = { controller.changeMode(local.id, if (local.deliveryMode == "prompt") "path" else "prompt") },
                        enabled = !model.sending && local.uploaded == null, modifier = Modifier.size(32.dp)) {
                        Icon(if (local.deliveryMode == "prompt") Icons.Rounded.Image else Icons.AutoMirrored.Rounded.InsertDriveFile,
                            if (local.deliveryMode == "prompt") "Sending as image; tap to send as file" else "Sending as file; tap to send as image",
                            Modifier.size(18.dp))
                    }
                    IconButton(onClick = { controller.remove(local.id) }, enabled = !model.sending && !controller.importing,
                        modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun MessageAttachments(message: ChatMessage, model: ConnectionViewModel) {
    message.metadata?.attachments.orEmpty().forEach { attachment ->
        Surface(onClick = { model.attachments.openPreview(attachment) }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(attachmentIcon(attachment.mimeType), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${attachmentSize(attachment.size)} · ${attachment.mimeType}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Preview", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPreviewSheet(model: ConnectionViewModel) {
    val controller = model.attachments
    val attachment = controller.preview ?: return
    val file = controller.previewFile
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actionNotice by remember(attachment) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveSource by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = saveSource
        if (uri != null && source != null) scope.launch {
            saving = true
            actionNotice = runCatching { withContext(Dispatchers.IO) {
                source.inputStream().use { input -> requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) } }
            }; "Copy saved." }.getOrDefault("The copy could not be saved.")
            saving = false
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, file, attachment.mimeType) {
        value = if (file != null && attachment.mimeType in PROMPT_IMAGE_TYPES) withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                require(options.outWidth > 0 && options.outHeight > 0)
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        } else null
    }
    val text by produceState<String?>(null, file) {
        value = if (file != null && attachment.mimeType.startsWith("text/") && file.length() <= 200_000) withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrNull()
        } else null
    }
    ModalBottomSheet(onDismissRequest = controller::closePreview, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(attachmentIcon(attachment.mimeType))
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${attachmentSize(attachment.size)} · ${attachment.mimeType}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = controller::closePreview) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (controller.previewBusy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            controller.previewError?.let { ErrorNotice(it) }
            actionNotice?.let { InfoNotice(it) }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    bitmap != null -> Image(requireNotNull(bitmap), contentDescription = attachment.name, modifier = Modifier.fillMaxSize())
                    text != null -> CodeLines(requireNotNull(text), Modifier.fillMaxSize())
                    file != null -> EmptyState(attachmentIcon(attachment.mimeType), "Ready", "Open it in another app or save a copy.")
                    !controller.previewBusy -> FilledTonalButton(onClick = { controller.openPreview(attachment) }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Load attachment")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".attachments", requireNotNull(file))
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open attachment"))
                    }.onFailure { actionNotice = "No app could open this file. You can save a copy instead." }
                }, enabled = file != null && !saving) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open with…")
                }
                Button(onClick = { saveSource = file; save.launch(safeAttachmentName(attachment.name)) }, enabled = file != null && !saving,
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save a copy")
                }
            }
        }
    }
}

private fun attachmentIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Rounded.Image
    mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
    mimeType.startsWith("video/") -> Icons.Rounded.VideoFile
    mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml") -> Icons.Rounded.Description
    mimeType == "application/pdf" -> Icons.Rounded.PictureAsPdf
    mimeType.contains("zip") || mimeType.contains("tar") -> Icons.Rounded.FolderZip
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

private fun attachmentSize(size: Long): String = if (size >= 1024 * 1024) "${(size * 10 / (1024 * 1024)).toDouble() / 10} MiB" else if (size >= 1024) "${(size + 1023) / 1024} KiB" else "$size B"
