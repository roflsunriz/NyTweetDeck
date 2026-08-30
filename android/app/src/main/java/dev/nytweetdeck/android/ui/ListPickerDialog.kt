package dev.nytweetdeck.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.ListOption
import dev.nytweetdeck.android.model.ListPickerScope
import dev.nytweetdeck.android.model.ListPickerState
import dev.nytweetdeck.android.model.TimelineLoadStatus

@Composable
internal fun ListPickerDialog(
    state: ListPickerState,
    onScopeChange: (ListPickerScope) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (ListOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchInput by rememberSaveable { mutableStateOf(state.searchQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("list-picker-dialog"),
        title = { Text(stringResource(R.string.list_timeline)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ListPickerScope.entries.forEach { scope ->
                        val label = when (scope) {
                            ListPickerScope.MINE -> stringResource(R.string.list_picker_mine)
                            ListPickerScope.SUGGESTED -> stringResource(R.string.list_picker_suggested)
                            ListPickerScope.SEARCH -> stringResource(R.string.search)
                        }
                        FilterChip(
                            selected = state.selectedScope == scope,
                            onClick = { onScopeChange(scope) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("list-scope-${scope.name.lowercase()}"),
                        )
                    }
                }
                if (state.selectedScope == ListPickerScope.SEARCH) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it.take(100) },
                        modifier = Modifier.fillMaxWidth().testTag("list-search-input"),
                        label = { Text(stringResource(R.string.list_search_query)) },
                        singleLine = true,
                    )
                    Button(
                        onClick = { onSearch(searchInput) },
                        enabled = searchInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("list-search-submit"),
                    ) {
                        Text(stringResource(R.string.search))
                    }
                }
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp).testTag("list-picker-refreshing"),
                    )
                }
                if (state.refreshFailed || state.status == TimelineLoadStatus.FAILED) {
                    Text(
                        stringResource(R.string.target_lookup_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("list-picker-failed"),
                    )
                }
                state.visibleOptions.take(50).forEach { option ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp)
                            .testTag("list-option-${option.id}"),
                    ) {
                        Text(option.name, fontWeight = FontWeight.SemiBold)
                        option.ownerUsername?.let {
                            Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        option.description?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-list-picker")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
