package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiFunctionCategory
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem

/**
 * Per-function API assignment (P3.5) — faithful port of iOS APIFunctionAssignmentView.
 * Lists every [ApiFunction] grouped by category; each can be pointed at a specific config or left
 * on "default" (the active config). Only chat / memory-summary are wired today; the rest are
 * placeholders for P4–P11 features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiFunctionAssignmentScreen(
    onBack: () -> Unit,
    viewModel: ApiFunctionAssignmentViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val active by viewModel.activeConfig.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_fn_assign_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentMaxWidth(),
        ) {
            ApiFunctionCategory.entries.forEach { category ->
                // 每类别一个 item：SettingsSection 包该类全部功能行（功能数固定少量·不再逐行 items·§4.A6）。
                item(key = "cat_${category.name}") {
                    SettingsSection(title = categoryLabel(category)) {
                        category.functions.forEach { fn ->
                            FunctionAssignmentRow(
                                function = fn,
                                configs = configs,
                                activeName = active?.let { "${it.providerName} ${it.modelName}" },
                                assignedUuid = assignments[fn],
                                onSelect = { uuid -> viewModel.setAssignment(fn, uuid) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionAssignmentRow(
    function: ApiFunction,
    configs: List<ApiConfigEntity>,
    activeName: String?,
    assignedUuid: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = activeName?.let { stringResource(R.string.api_fn_default_named, it) }
        ?: stringResource(R.string.api_fn_default)
    val assignedConfig = assignedUuid?.let { id -> configs.firstOrNull { it.uuid == id } }
    val selectedLabel = assignedConfig?.let { "${it.providerName} ${it.modelName}" } ?: defaultLabel

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(function.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            function.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppDropdownField(
            value = selectedLabel,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            AppDropdownMenuItem(
                text = defaultLabel,
                selected = assignedUuid == null,
                onClick = { onSelect(null); expanded = false },
            )
            configs.forEach { cfg ->
                AppDropdownMenuItem(
                    text = "${cfg.providerName} ${cfg.modelName}",
                    selected = assignedUuid == cfg.uuid,
                    onClick = { onSelect(cfg.uuid); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun categoryLabel(category: ApiFunctionCategory): String = when (category) {
    ApiFunctionCategory.CONVERSATION -> stringResource(R.string.api_fn_cat_conversation)
    ApiFunctionCategory.BACKGROUND -> stringResource(R.string.api_fn_cat_background)
    ApiFunctionCategory.CONTENT -> stringResource(R.string.api_fn_cat_content)
}
