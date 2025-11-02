package de.fabianthomas.intentbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var notificationsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var statusSame: TextView
    private lateinit var statusOther: TextView
    private lateinit var tlsSectionLabel: TextView
    private lateinit var tlsLocalFingerprint: TextView
    private lateinit var tlsPeerStatus: TextView
    private lateinit var tlsPeerFingerprint: TextView
    private lateinit var tlsResetPinButton: Button
    private lateinit var tlsRegenerateButton: Button
    private lateinit var routingBrowserSwitch: MaterialSwitch
    private lateinit var routingMapsSwitch: MaterialSwitch
    private lateinit var routingYoutubeSwitch: MaterialSwitch
    private lateinit var routingMailSwitch: MaterialSwitch
    private lateinit var routingTelSwitch: MaterialSwitch
    private var activeRole: ProfileRoleStore.Role = ProfileRoleStore.Role.PRIVATE_SPACE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.topAppBar))

        notificationsPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    SocketServerService.ensureRunning(this)
                } else {
                    Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
                }
            }

        runCatching { TlsIdentityStore.ensureIdentity(this) }

        statusSame = findViewById(R.id.statusSameProfile)
        statusOther = findViewById(R.id.statusOtherProfile)
        tlsSectionLabel = findViewById(R.id.tlsSectionLabel)
        tlsLocalFingerprint = findViewById(R.id.tlsLocalFingerprint)
        tlsPeerStatus = findViewById(R.id.tlsPeerStatus)
        tlsPeerFingerprint = findViewById(R.id.tlsPeerFingerprint)
        tlsResetPinButton = findViewById(R.id.tlsResetPinButton)
        tlsRegenerateButton = findViewById(R.id.tlsRegenerateButton)
        routingBrowserSwitch = findViewById(R.id.routingBrowserSwitch)
        routingMapsSwitch = findViewById(R.id.routingMapsSwitch)
        routingYoutubeSwitch = findViewById(R.id.routingYoutubeSwitch)
        routingMailSwitch = findViewById(R.id.routingMailSwitch)
        routingTelSwitch = findViewById(R.id.routingTelSwitch)

        val roleGroup = findViewById<RadioGroup>(R.id.profileRoleGroup)
        activeRole = ProfileRoleStore.getRole(this)
        when (activeRole) {
            ProfileRoleStore.Role.PRIVATE_SPACE -> roleGroup.check(R.id.radioPrivateRole)
            ProfileRoleStore.Role.PERSONAL_SPACE -> roleGroup.check(R.id.radioPersonalRole)
        }

        roleGroup.setOnCheckedChangeListener { _, checkedId ->
            val newRole = when (checkedId) {
                R.id.radioPersonalRole -> ProfileRoleStore.Role.PERSONAL_SPACE
                else -> ProfileRoleStore.Role.PRIVATE_SPACE
            }
            if (newRole != activeRole) {
                ProfileRoleStore.setRole(this, newRole)
                activeRole = newRole
                stopService(Intent(this, SocketServerService::class.java))
                TlsIdentityStore.regenerateIdentity(this)
                Toast.makeText(
                    this,
                    getString(R.string.role_updated_to, ProfileRoleStore.describe(newRole)),
                    Toast.LENGTH_SHORT
                ).show()
                refreshTlsSection()
                refreshStatuses()
                refreshRoutingSwitches()
            }
        }

        findViewById<Button>(R.id.statusRefreshButton).setOnClickListener { refreshStatuses() }
        findViewById<Button>(R.id.socketRestartButton).setOnClickListener {
            stopService(Intent(this, SocketServerService::class.java))
            ensureSocketListener()
            Toast.makeText(this, R.string.socket_restart_done, Toast.LENGTH_SHORT).show()
            refreshStatuses()
        }
        findViewById<Button>(R.id.openByDefaultButton).setOnClickListener { openByDefaultSettings() }

        setupLinkButton(R.id.linkButtonMapsShort, "https://maps.app.goo.gl/Lr4bVskgxWTkQanG6")
        setupLinkButton(R.id.linkButtonMapsFull, "https://www.google.com/maps/search/?api=1&query=lumen+field")
        setupLinkButton(R.id.linkButtonYouTube, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        setupLinkButton(R.id.linkButtonWebsite, "https://www.wikipedia.org/")
        setupLinkButton(R.id.linkButtonMail, "mailto:example@example.com?subject=Hello&body=Hi%20there!")
        setupLinkButton(R.id.linkButtonTel, "tel:+15551234567")

        setupRoutingToggles()

        tlsResetPinButton.setOnClickListener { resetPinnedCertificate() }
        tlsRegenerateButton.setOnClickListener { regenerateCertificate() }

        refreshTlsSection()
        refreshStatuses()
        refreshRoutingSwitches()
    }

    override fun onResume() {
        super.onResume()
        ensureSocketListener()
        refreshTlsSection()
        refreshStatuses()
        refreshRoutingSwitches()
    }

    private fun openByDefaultSettings() {
        val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.open_by_default_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatuses() {
        statusSame.text = getString(R.string.status_same_profile_unknown)
        statusSame.setTextColor(Color.DKGRAY)
        statusOther.text = getString(R.string.status_other_profile_unknown)
        statusOther.setTextColor(Color.DKGRAY)

        Thread {
            val role = ProfileRoleStore.getRole(this@MainActivity)
            val samePort = ProfileRoleStore.listeningPort(role)
            val otherPort = ProfileRoleStore.targetPort(role)

            val sameResult = if (SocketServerService.isRunning()) {
                SocketServerService.ping(applicationContext, samePort, 1_000)
            } else {
                SocketServerService.PingResult(success = false, authError = false)
            }
            val otherResult = SocketServerService.ping(applicationContext, otherPort, 1_000)

            runOnUiThread {
                applyStatus(
                    statusSame,
                    getString(R.string.status_same_profile, statusLabel(sameResult)),
                    determineState(sameResult)
                )
                applyStatus(
                    statusOther,
                    getString(R.string.status_other_profile, statusLabel(otherResult)),
                    determineState(otherResult)
                )
            }
        }.start()
    }

    private fun applyStatus(view: TextView, text: String, state: StatusState) {
        view.text = text
        val color = when (state) {
            StatusState.ACTIVE -> Color.parseColor("#2E7D32")
            StatusState.AUTH_ERROR -> Color.parseColor("#EF6C00")
            StatusState.INACTIVE -> Color.parseColor("#C62828")
            StatusState.UNKNOWN -> Color.DKGRAY
        }
        view.setTextColor(color)
    }

    private fun ensureSocketListener() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                SocketServerService.ensureRunning(this)
            }
            shouldShowRequestPermissionRationale(permission) -> {
                Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
                notificationsPermissionLauncher.launch(permission)
            }
            else -> notificationsPermissionLauncher.launch(permission)
        }
    }

    private fun statusLabel(result: SocketServerService.PingResult): String {
        return when {
            result.success -> getString(R.string.status_label_active)
            result.authError -> getString(R.string.status_label_auth_error)
            else -> getString(R.string.status_label_inactive)
        }
    }

    private fun determineState(result: SocketServerService.PingResult): StatusState {
        return when {
            result.success -> StatusState.ACTIVE
            result.authError -> StatusState.AUTH_ERROR
            else -> StatusState.INACTIVE
        }
    }

    private fun setupRoutingToggles() {
        routingBrowserSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(LinkRoutingPrefs.LinkCategory.BROWSER, isChecked)
        }
        routingMapsSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(LinkRoutingPrefs.LinkCategory.MAPS, isChecked)
        }
        routingYoutubeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(LinkRoutingPrefs.LinkCategory.YOUTUBE, isChecked)
        }
        routingMailSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(LinkRoutingPrefs.LinkCategory.MAIL, isChecked)
        }
        routingTelSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(LinkRoutingPrefs.LinkCategory.TEL, isChecked)
        }
    }

    private fun refreshRoutingSwitches() {
        val role = ProfileRoleStore.getRole(this)
        updateSwitch(routingBrowserSwitch, LinkRoutingPrefs.LinkCategory.BROWSER, role)
        updateSwitch(routingMapsSwitch, LinkRoutingPrefs.LinkCategory.MAPS, role)
        updateSwitch(routingYoutubeSwitch, LinkRoutingPrefs.LinkCategory.YOUTUBE, role)
        updateSwitch(routingMailSwitch, LinkRoutingPrefs.LinkCategory.MAIL, role)
        updateSwitch(routingTelSwitch, LinkRoutingPrefs.LinkCategory.TEL, role)
    }

    private fun updateSwitch(
        switch: MaterialSwitch,
        category: LinkRoutingPrefs.LinkCategory,
        currentRole: ProfileRoleStore.Role
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = LinkRoutingPrefs.preferredRole(this, category) == currentRole
        switch.setOnCheckedChangeListener { _, isChecked ->
            updateRoutingPreference(category, isChecked)
        }
    }

    private fun updateRoutingPreference(
        category: LinkRoutingPrefs.LinkCategory,
        openHere: Boolean
    ) {
        val currentRole = ProfileRoleStore.getRole(this)
        val targetRole = if (openHere) currentRole else ProfileRoleStore.opposite(currentRole)
        if (LinkRoutingPrefs.preferredRole(this, category) == targetRole) {
            refreshRoutingSwitches()
            return
        }
        LinkRoutingPrefs.setPreferredRole(this, category, targetRole)
        CrossSpaceHandoff.syncRoutingPreference(this, category, targetRole)
        val categoryLabel = getString(category.labelRes)
        val roleLabel = ProfileRoleStore.describe(targetRole)
        Toast.makeText(this, getString(R.string.routing_updated_toast, categoryLabel, roleLabel), Toast.LENGTH_SHORT).show()
        refreshRoutingSwitches()
    }

    private fun setupLinkButton(buttonId: Int, url: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            val linkIntent = Intent(this, LinkRouterActivity::class.java).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(linkIntent) }.onFailure {
                Toast.makeText(this, getString(R.string.error_open_link, url), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetPinnedCertificate() {
        TlsIdentityStore.clearPinnedPeerCertificate(this)
        Toast.makeText(this, R.string.tls_pin_reset_done, Toast.LENGTH_SHORT).show()
        ensureSocketListener()
        refreshTlsSection()
        refreshStatuses()
    }

    private fun regenerateCertificate() {
        TlsIdentityStore.regenerateIdentity(this)
        Toast.makeText(this, R.string.tls_regenerated, Toast.LENGTH_SHORT).show()
        ensureSocketListener()
        refreshTlsSection()
        refreshStatuses()
    }

    private fun refreshTlsSection() {
        val identity = TlsIdentityStore.ensureIdentity(this)
        val roleLabel = ProfileRoleStore.describe(activeRole)
        tlsSectionLabel.text = getString(R.string.tls_section, roleLabel)
        tlsLocalFingerprint.text = identity.fingerprint

        val pinned = TlsIdentityStore.pinnedPeerFingerprint(this)
        val authError = TlsIdentityStore.hasAuthError(this)
        if (pinned == null) {
            tlsPeerStatus.text = getString(R.string.tls_peer_status_waiting)
            tlsPeerStatus.setTextColor(Color.DKGRAY)
            tlsPeerFingerprint.text = getString(R.string.tls_fingerprint_placeholder)
            tlsResetPinButton.isEnabled = false
        } else {
            tlsPeerFingerprint.text = pinned
            if (authError) {
                tlsPeerStatus.text = getString(R.string.tls_peer_status_mismatch)
                tlsPeerStatus.setTextColor(Color.parseColor("#EF6C00"))
            } else {
                tlsPeerStatus.text = getString(R.string.tls_peer_status_ready)
                tlsPeerStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            tlsResetPinButton.isEnabled = true
        }
    }

    private enum class StatusState {
        ACTIVE,
        INACTIVE,
        AUTH_ERROR,
        UNKNOWN
    }
}
