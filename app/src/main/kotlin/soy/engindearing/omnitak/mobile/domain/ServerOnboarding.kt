package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CSREnrollmentService
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
import soy.engindearing.omnitak.mobile.data.ImportedServerConfig
import soy.engindearing.omnitak.mobile.data.ServerImportPreview
import soy.engindearing.omnitak.mobile.data.TAKServer

/**
 * The single place a server parsed from a deep link or QR becomes a
 * connected [TAKServer]. Both on-ramps (`MainActivity` VIEW intents and the
 * in-app `ServerEnrollScanRoute`) publish an [ImportedServerConfig] to
 * [OmniTAKApp.pendingServerImport]; `AppNav` shows the confirmation dialog;
 * only the dialog's confirm button calls [addConfirmed]
 * (audit 2026-09-14, H3).
 *
 * Runs on [OmniTAKApp.appScope] so it survives the scanner route popping
 * itself off the back stack (#174). Result toasts are marshalled to Main.
 */
object ServerOnboarding {
    private const val TAG = "ServerOnboarding"

    fun addConfirmed(app: OmniTAKApp, cfg: ImportedServerConfig) {
        val preview = ServerImportPreview.from(cfg)
        val appCtx = app.applicationContext
        if (!cfg.needsEnrollment) {
            app.serverManager.addServer(DeepLinkImport.toServer(cfg))
            Log.i(TAG, "Added ${preview.logLine}")
            Toast.makeText(appCtx, "Added server: ${cfg.name} (${preview.endpoint})", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(appCtx, "Enrolling with ${cfg.host}…", Toast.LENGTH_SHORT).show()
        app.appScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    CSREnrollmentService(app.certVault).enroll(
                        CSREnrollmentService.Config(
                            host = cfg.host,
                            enrollmentPort = cfg.enrollmentPort,
                            username = cfg.username!!,
                            password = cfg.password!!,
                            trustSelfSigned = cfg.trustSelfSigned,
                        ),
                    )
                }
            }
            result.onSuccess { enrolled ->
                app.serverManager.addServer(
                    TAKServer(
                        name = cfg.name,
                        host = cfg.host,
                        port = cfg.port,
                        protocol = ConnectionProtocol.TLS.wire,
                        useTLS = true,
                        username = cfg.username,
                        // password is the login/enrollment credential, not the .p12 passphrase
                        password = null,
                        certificateName = enrolled.certificateName,
                        certificatePassword = enrolled.certificatePassword,
                        // Pin the enrollment CA so the connection validates the
                        // server's private-CA cert. Matches EnrollServerScreen.
                        caCertificateName = enrolled.caCertificateName,
                    ),
                )
                Log.i(TAG, "Enrolled and added ${preview.logLine}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "Enrolled & connected: ${cfg.name}", Toast.LENGTH_LONG).show()
                }
            }
            result.onFailure { e ->
                Log.e(TAG, "Enrollment failed for ${cfg.host}: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appCtx,
                        "Enrollment failed: ${e.message ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
