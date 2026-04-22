package com.kolod.dcimorganizer

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kolod.dcimorganizer.ui.theme.DCIMOrganizerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Teal-600 (Tailwind). Chosen for both light and dark themes — it has solid contrast against
// white text and reads well on black and white surfaces alike. If a fuller brand palette
// emerges later, move this into ui/theme/Color.kt and thread it through the color scheme.
private val BrandAccent = Color(0xFF0D9488)

// Duration of the status-message fade. Short enough to feel snappy on repeated taps, long
// enough that rapid state changes don't look like a flicker.
private const val STATUS_FADE_MS = 280

// Represents every distinct state the organizer UI can be in.
// Carrying typed data here lets OrganizeScreen derive all display strings
// via stringResource / pluralStringResource in the composable body, which
// is the only place those Compose resource APIs may legally be called.
private sealed class OrganizerUiState {
    object Idle : OrganizerUiState()
    object Organizing : OrganizerUiState()
    // total  = cumulative files moved; someSkipped = pending-permission URIs remain
    data class Done(val total: Int, val someSkipped: Boolean = false) : OrganizerUiState()
    data class Error(val rawMessage: String?) : OrganizerUiState()
    object PermissionRequired : OrganizerUiState()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DCIMOrganizerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OrganizeScreen(
                        modifier = Modifier.padding(innerPadding),
                        organizer = PhotoOrganizer(this)
                    )
                }
            }
        }
    }
}

@Composable
fun OrganizeScreen(modifier: Modifier = Modifier, organizer: PhotoOrganizer) {
    // Single source of truth for UI state. Callbacks only write state; they never
    // touch resources directly, so there are no LocalContextGetResourceValueCall violations.
    var uiState by remember { mutableStateOf<OrganizerUiState>(OrganizerUiState.Idle) }
    // Running total across multiple passes (initial run + retry after grant dialog).
    var movedSoFar by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Derive display strings from state here, in composable context, using the
    // Compose resource APIs (stringResource / pluralStringResource) as lint requires.
    val statusMessage: String = when (val s = uiState) {
        is OrganizerUiState.Idle -> ""
        is OrganizerUiState.Organizing -> stringResource(R.string.status_organizing)
        is OrganizerUiState.Done -> when {
            s.someSkipped -> pluralStringResource(R.plurals.status_done_some_skipped, s.total, s.total)
            s.total == 0  -> stringResource(R.string.status_already_organized)
            else          -> pluralStringResource(R.plurals.status_done, s.total, s.total)
        }
        is OrganizerUiState.Error -> stringResource(
            R.string.status_error,
            s.rawMessage ?: stringResource(R.string.error_unknown)
        )
        is OrganizerUiState.PermissionRequired -> stringResource(R.string.status_permission_required)
    }
    val isLoading = uiState is OrganizerUiState.Organizing

    // Launcher for the MediaStore "grant write access" system dialog.
    // On OK, re-run organize; any URIs still failing after that just get reported as skipped.
    val writeRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            uiState = OrganizerUiState.Organizing
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { organizer.organize() }
                }
                outcome.onFailure { e ->
                    uiState = OrganizerUiState.Error(e.message)
                }.onSuccess { r ->
                    val total = movedSoFar + r.movedCount
                    movedSoFar = total
                    uiState = OrganizerUiState.Done(
                        total = total,
                        someSkipped = r.pendingPermissionUris.isNotEmpty()
                    )
                }
            }
        } else {
            uiState = OrganizerUiState.Done(total = movedSoFar, someSkipped = true)
        }
    }

    val startOrganize: () -> Unit = {
        movedSoFar = 0
        runOrganizer(
            scope, organizer,
            baselineCount = 0,
            onStart = { uiState = OrganizerUiState.Organizing },
            onDone = { total ->
                movedSoFar = total
                uiState = OrganizerUiState.Done(total = total, someSkipped = false)
            },
            onError = { msg ->
                uiState = OrganizerUiState.Error(msg)
            },
            onRequestWrite = { total, uris ->
                movedSoFar = total
                // Keep a tentative "Done: N" visible while the system dialog is in front.
                uiState = OrganizerUiState.Done(total = total, someSkipped = false)
                launchWriteRequest(context.contentResolver, uris, writeRequestLauncher)
            }
        )
    }

    // Launcher for READ_MEDIA_IMAGES + READ_MEDIA_VIDEO runtime permissions.
    val readPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startOrganize()
        } else {
            uiState = OrganizerUiState.PermissionRequired
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Status message, top-anchored. Crossfade handles two things we care about:
        //  - repeated taps: the old "Done: N moved" fades out as the new "Organizing…" fades in
        //  - empty -> message: the first status appears smoothly instead of popping in
        // Driving Crossfade by the message text (not a separate visible flag) means every
        // change in text animates, including done -> organizing -> done cycles.
        Crossfade(
            targetState = statusMessage,
            animationSpec = tween(durationMillis = STATUS_FADE_MS),
            label = "status",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 48.dp, start = 24.dp, end = 24.dp)
        ) { message ->
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // When message is empty we render nothing — Crossfade still fades the previous
            // content out, so there's no abrupt disappearance.
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(120.dp),
                strokeWidth = 8.dp
            )
        } else {
            Button(
                onClick = {
                    if (hasReadMediaPermission(context)) {
                        startOrganize()
                    } else {
                        readPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO
                            )
                        )
                    }
                },
                // Order matters: align and padding must come BEFORE size, so the button's
                // own bounds stay 180x180 (square) and CircleShape renders an actual circle.
                // Putting padding after size would shrink the inner height and produce an oval.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp)
                    .size(180.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandAccent,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 10.dp,
                    pressedElevation = 4.dp,
                    hoveredElevation = 14.dp,
                    focusedElevation = 12.dp,
                    disabledElevation = 0.dp
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = stringResource(R.string.organize),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }
    }
}

private fun hasReadMediaPermission(context: Context): Boolean {
    val images = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_MEDIA_IMAGES
    ) == PackageManager.PERMISSION_GRANTED
    val videos = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED
    return images && videos
}

private fun launchWriteRequest(
    resolver: ContentResolver,
    uris: List<Uri>,
    launcher: ActivityResultLauncher<IntentSenderRequest>
) {
    val pendingIntent = MediaStore.createWriteRequest(resolver, uris)
    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
    launcher.launch(request)
}

private fun runOrganizer(
    scope: CoroutineScope,
    organizer: PhotoOrganizer,
    baselineCount: Int,
    onStart: () -> Unit,
    onDone: (total: Int) -> Unit,
    onError: (rawMessage: String?) -> Unit,
    onRequestWrite: (total: Int, uris: List<Uri>) -> Unit
) {
    onStart()
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { organizer.organize() }
        }
        result.onFailure { e ->
            // Hand the raw message to the caller — localized "Error: …" formatting happens
            // there, where we have access to Context/resources.
            onError(e.message)
        }.onSuccess { r ->
            val total = baselineCount + r.movedCount
            if (r.pendingPermissionUris.isNotEmpty()) {
                onRequestWrite(total, r.pendingPermissionUris)
            } else {
                onDone(total)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DCIMOrganizerTheme {
        OrganizeScreen(
            organizer = PhotoOrganizer(context = LocalContext.current)
        )
    }
}
