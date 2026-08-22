package com.streamhub.mobile.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamhub.core.download.DownloadRecord

/**
 * Every download on the device: moving, paused, failed, or ready to watch.
 *
 * A paused or failed row keeps its progress on screen. That is not decoration —
 * seeing "82%" beside Resume is the promise that resuming continues from there,
 * which is the behaviour that used to be missing everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    repository: DownloadsRepository,
    onBack: () -> Unit,
    onPlay: (DownloadRecord) -> Unit,
) {
    val downloads by repository.downloads.collectAsState()
    val active by repository.active.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing downloaded", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use the download button beside any source on a title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(downloads, key = { it.id }) { record ->
                DownloadRow(
                    record = record,
                    isActive = record.id in active,
                    onPlay = { onPlay(record) },
                    onPause = { repository.pause(record.id) },
                    onResume = { repository.resume(record.id) },
                    onDelete = { repository.delete(record.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    isActive: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = record.finished, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    text = record.describe(isActive),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.error != null && !isActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when {
                record.finished -> IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
                isActive -> IconButton(onClick = onPause) {
                    // The pause glyph, drawn as text: the icon set in this app
                    // has no Pause and one glyph is not worth a new dependency.
                    Text("❚❚", style = MaterialTheme.typography.labelLarge)
                }
                else -> IconButton(onClick = onResume) {
                    Icon(Icons.Default.Refresh, contentDescription = "Resume")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Clear, contentDescription = "Delete")
            }
        }

        if (!record.finished) {
            LinearProgressIndicator(
                progress = { record.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private fun DownloadRecord.describe(isActive: Boolean): String {
    val what = listOfNotNull(seasonLabel, episodeLabel, sourceLabel).joinToString(" · ")
    val megabytes = bytesWritten / (1024 * 1024)
    return when {
        finished -> listOf(what, "${megabytes} MB").filter { it.isNotBlank() }.joinToString("  ·  ")
        isActive -> "$what  ·  ${(progress * 100).toInt()}% · ${megabytes} MB"
        error != null -> "$what  ·  stopped at ${(progress * 100).toInt()}% — tap to retry"
        else -> "$what  ·  paused at ${(progress * 100).toInt()}%"
    }
}
