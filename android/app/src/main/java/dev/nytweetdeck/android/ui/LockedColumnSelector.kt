package dev.nytweetdeck.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.DeckColumn

/** Keeps every column reachable while horizontal touch scrolling is locked. */
@Composable
internal fun LockedColumnSelector(
    columns: List<DeckColumn>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("locked-column-selector"),
        ) {
            Text(stringResource(R.string.column_select) + ": " + columns.getOrNull(selectedIndex)?.title.orEmpty())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            columns.forEachIndexed { index, column ->
                DropdownMenuItem(
                    text = { Text(column.title) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                    modifier = Modifier.testTag("locked-column-select-" + column.id),
                )
            }
        }
    }
}
