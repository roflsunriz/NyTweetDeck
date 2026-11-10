package dev.nytweetdeck.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.RemovedColumnNotice
import kotlinx.coroutines.delay

@Composable
internal fun ColumnRemovalNotice(
    notices: List<RemovedColumnNotice>,
    onRestore: (String) -> Unit,
    onExpire: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    notices.forEach { notice ->
            LaunchedEffect(notice.id, notice.deadlineMillis) {
                delay((notice.deadlineMillis - System.nanoTime() / 1_000_000).coerceAtLeast(0))
                onExpire(notice.id)
            }
    }
    Column(modifier.padding(12.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        notices.asReversed().forEach { notice ->
            Snackbar(
                modifier = Modifier.testTag("column-removed-" + notice.id),
                action = {
                    TextButton(onClick = { onRestore(notice.id) },
                        modifier = Modifier.testTag("column-restore-" + notice.id)) {
                        Text(stringResource(R.string.column_restore))
                    }
                },
            ) { Text(stringResource(R.string.column_removed) + ": " + notice.title) }
        }
    }
}
