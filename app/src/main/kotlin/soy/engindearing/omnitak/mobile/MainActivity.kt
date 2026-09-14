package soy.engindearing.omnitak.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
import soy.engindearing.omnitak.mobile.data.ServerImportPreview
import soy.engindearing.omnitak.mobile.ui.navigation.AppNav
import soy.engindearing.omnitak.mobile.ui.onboarding.OnboardingFlow
import soy.engindearing.omnitak.mobile.ui.onboarding.OnboardingManager
import soy.engindearing.omnitak.mobile.ui.theme.OmniTAKTheme
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TacticalBackground.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(TacticalBackground.toArgb()),
        )
        setContent {
            OmniTAKTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val context = LocalContext.current
                    var onboardingDone by remember {
                        mutableStateOf(OnboardingManager.isComplete(context))
                    }
                    if (!onboardingDone) {
                        OnboardingFlow(onComplete = {
                            OnboardingManager.markComplete(context)
                            onboardingDone = true
                        })
                    } else {
                        AppNav()
                    }
                }
            }
        }

        handleImportIntent(intent)
        handleChatNotificationIntent(intent)

        // Re-open the TLS socket on every foreground resume if Android
        // killed the read loop while we were backgrounded (Doze, app
        // standby, network swap). Cold-launch reconnect lives in
        // ServerManager.hydrate; this hook handles foreground-resume.
        // Issue #6.
        //
        // Issue #75 — also force an immediate location refresh on resume
        // (fused cache + active single-shot) instead of waiting for the
        // next passive interval tick, so the self-marker snaps back to a
        // live position right after screen-on. Foreground-only; no
        // background-location permission involved.
        val app = applicationContext as OmniTAKApp
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    app.serverManager.reconnectIfNeeded()
                    app.locationProvider.requestImmediateFix()
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
        handleChatNotificationIntent(intent)
    }

    /**
     * #173 follow-up — a mesh-chat notification tap carries
     * [MeshChatNotifier.EXTRA_CONVERSATION_ID]. Publish it to
     * [OmniTAKApp.pendingChatConversation] so AppNav opens that thread.
     */
    private fun handleChatNotificationIntent(intent: Intent?) {
        val convoId = intent?.getStringExtra(
            soy.engindearing.omnitak.mobile.domain.MeshChatNotifier.EXTRA_CONVERSATION_ID,
        ) ?: return
        if (convoId.isBlank()) return
        (applicationContext as OmniTAKApp).pendingChatConversation.value = convoId
    }

    /**
     * GAP-105 rest / #100 — handle `tak://` / `atak://` / `omnitak://` deep
     * links carrying a server-onboarding payload (enrollment QR, connect link,
     * or config profile). Singletask launchMode means a second scan while the
     * app is open re-enters via [onNewIntent] instead of spawning a new task.
     */
    private fun handleImportIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        // Profile import takes priority — check BEFORE server-config because
        // both share the `omnitak://` scheme (profile = omnitak://profile?d=…).
        // Route through the same ImportPreviewDialog used by the in-app scanner
        // so the user can review and confirm before the profile is applied.
        if (DeepLinkImport.isProfileConfig(uri)) {
            val profile = DeepLinkImport.parseProfileConfig(uri)
            if (profile == null) {
                Toast.makeText(this, "Invalid profile QR code", Toast.LENGTH_LONG).show()
                return
            }
            val app = applicationContext as OmniTAKApp
            // Publish to the pending-import flow; AppNav observes it and
            // shows ImportPreviewDialog — same flow as the in-app QR scanner.
            app.pendingProfileImport.value = profile
            Log.i("OmniTAK", "Queued deep-link profile import '${profile.name}' for user review")
            return
        }

        // #100 — the standard ATAK / TAK Server / ArgusTAK enrollment QR
        // (`tak://…/enroll?host=&username=&token=`). Check BEFORE isServerConfig:
        // an `atak://…/enroll` link also satisfies the connect-form matcher, and
        // we want it to CSR-enroll (token = enrollment secret) rather than be
        // added cert-less and rejected at the mTLS handshake.
        if (DeepLinkImport.isEnrollLink(uri)) {
            val enrollCfg = DeepLinkImport.parseEnrollLink(uri)
            if (enrollCfg == null) {
                Toast.makeText(
                    this,
                    "Enrollment link missing host, username, or token",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            // Never enroll straight from a link: publish for user confirmation
            // (ServerImportConfirmDialog in AppNav) — audit 2026-09-14, H3.
            app().pendingServerImport.value = enrollCfg
            Log.i("OmniTAK", "Queued ${ServerImportPreview.from(enrollCfg).logLine} for user review")
            return
        }

        if (!DeepLinkImport.isServerConfig(uri)) return

        val cfg = DeepLinkImport.parseServerConfig(uri)
        if (cfg == null) {
            Toast.makeText(
                this,
                "Onboarding link missing host or port",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // Same rule for the connect form: the user confirms before anything
        // is added or connected. ServerOnboarding.addConfirmed decides between
        // CSR enrollment (TLS + credentials) and a cert-less add.
        app().pendingServerImport.value = cfg
        Log.i("OmniTAK", "Queued ${ServerImportPreview.from(cfg).logLine} for user review")
    }

    private fun app(): OmniTAKApp = applicationContext as OmniTAKApp
}
