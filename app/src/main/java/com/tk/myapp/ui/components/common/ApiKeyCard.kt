package com.tk.myapp.ui.components.common

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApiKeyCard(
    title: String,
    shape: Shape,
    savedApiKey: String?,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var apiKeyText by remember(savedApiKey) { mutableStateOf(savedApiKey ?: "") }
    val isSaved = savedApiKey != null
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val providerName = title.removeSuffix(" API Key")

    Segment(shape = shape) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isSaved && savedApiKey != null) {
                SegmentRow(
                    headline = providerName,
                    supporting = "Saved, ending in ${savedApiKey.takeLast(4)}",
                    contentPadding = PaddingValues(0.dp),
                    trailing = {
                        FilledTonalButton(
                            onClick = {
                                onReset()
                                apiKeyText = ""
                            },
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text("Remove")
                        }
                    }
                )
            } else {
                SegmentRow(
                    headline = providerName,
                    supporting = "Not set",
                    contentPadding = PaddingValues(0.dp)
                )
                OutlinedTextField(
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it },
                    label = { Text("$providerName API key") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipData = clipboard?.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text
                                if (text != null) {
                                    apiKeyText = text.toString()
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "Paste from clipboard"
                            )
                        }
                    }
                )
                Button(
                    onClick = {
                        onSave(apiKeyText)
                        focusManager.clearFocus()
                    },
                    enabled = apiKeyText.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save key")
                }
            }
        }
    }
}
