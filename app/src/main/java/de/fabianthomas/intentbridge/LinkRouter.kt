package de.fabianthomas.intentbridge

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale

internal const val EXTRA_ROUTED = "de.fabianthomas.intentbridge.EXTRA_ROUTED"

object LinkRouter {

    fun handle(activity: ComponentActivity, intent: Intent?) {
        val isRouted = intent?.getBooleanExtra(EXTRA_ROUTED, false) == true
        val resolved = resolveIncoming(intent?.data)
        if (isRouted) {
            val target = resolved ?: intent?.data
            if (target != null) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.warning_link_bounced_back, target.toString()),
                    Toast.LENGTH_LONG
                ).show()
                val launchedChooser = activity.launchFallbackChooser(target)
                if (!launchedChooser) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.error_open_link, target.toString()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.warning_link_bounced_back_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
            activity.finish()
            return
        }
        val target = resolved ?: run {
            activity.finish()
            return
        }
        dispatch(activity, intent, target, allowShortResolution = true)
    }

    private fun dispatch(
        activity: ComponentActivity,
        intent: Intent?,
        uri: Uri,
        allowShortResolution: Boolean
    ) {
        if (allowShortResolution && LinkUtils.isMapsShort(uri)) {
            activity.showResolvingOverlay()
            Thread {
                val resolved = LinkUtils.resolveMapsShortLink(uri.toString())
                activity.runOnUiThread {
                    val finalUri = resolved?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    if (finalUri == null || LinkUtils.isMapsShort(finalUri) || finalUri == uri) {
                        activity.showResolveError()
                        activity.finish()
                        return@runOnUiThread
                    }
                    dispatch(activity, intent, finalUri, allowShortResolution = false)
                }
            }.start()
            return
        }

        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "mailto", "tel", "telprompt" -> {
                handleDirectIntent(activity, intent, uri)
                return
            }
        }

        if (isYouTube(uri)) {
            handleYouTube(activity, uri)
            return
        }

        if (isMaps(uri)) {
            handleMaps(activity, uri)
            return
        }

        handleGeneric(activity, uri)
    }

    private fun handleDirectIntent(
        activity: ComponentActivity,
        incoming: Intent?,
        uri: Uri
    ) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val category = when (scheme) {
            "mailto" -> LinkRoutingPrefs.LinkCategory.MAIL
            "tel", "telprompt" -> LinkRoutingPrefs.LinkCategory.TEL
            else -> null
        }
        if (category == null) {
            handleGeneric(activity, uri)
            return
        }

        val targetUri = if (category == LinkRoutingPrefs.LinkCategory.MAIL) {
            MailtoIntentUtils.buildMailtoString(incoming, uri)?.let(Uri::parse) ?: uri
        } else {
            uri
        }

        routeCategory(activity, category, targetUri) {
            activity.launchRoutedViewIntent(targetUri)
        }
    }

    private fun handleYouTube(activity: ComponentActivity, uri: Uri) {
        routeCategory(activity, LinkRoutingPrefs.LinkCategory.YOUTUBE, uri) {
            activity.launchRoutedViewIntent(uri)
        }
    }

    private fun handleMaps(activity: ComponentActivity, uri: Uri) {
        routeCategory(activity, LinkRoutingPrefs.LinkCategory.MAPS, uri) {
            activity.launchRoutedViewIntent(uri)
        }
    }

    private fun handleGeneric(activity: ComponentActivity, uri: Uri) {
        routeCategory(activity, LinkRoutingPrefs.LinkCategory.BROWSER, uri) {
            activity.launchRoutedViewIntent(uri)
        }
    }

    private fun routeCategory(
        activity: ComponentActivity,
        category: LinkRoutingPrefs.LinkCategory,
        uri: Uri,
        openLocal: () -> Boolean
    ) {
        val targetRole = LinkRoutingPrefs.preferredRole(activity, category)
        val currentRole = ProfileRoleStore.getRole(activity)
        if (currentRole == targetRole) {
            if (!openLocal()) {
                activity.showOpenError(uri)
            }
        } else {
            CrossSpaceHandoff.launch(activity, uri.toString())
            CrossSpaceMessages.showForwardToast(activity, targetRole)
        }
        activity.finish()
    }

    private fun resolveIncoming(data: Uri?): Uri? {
        if (data == null) return null
        return if (data.scheme == "xspace") {
            val inner = data.getQueryParameter("u")
            inner?.let { Uri.parse(it) }
        } else data
    }

    private fun isYouTube(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "youtu.be" ||
            host == "www.youtube.com" ||
            host == "youtube.com" ||
            host == "m.youtube.com" ||
            host == "music.youtube.com"
    }

    private fun isMaps(uri: Uri): Boolean {
        if (uri.scheme == "geo") return true
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host != null) {
            if (host == "maps.app.goo.gl") return true
            if (host.endsWith("google.com")) {
                val path = uri.path ?: ""
                if (path.startsWith("/maps")) return true
            }
        }
        return false
    }

    private fun ComponentActivity.showResolvingOverlay() {
        window.setBackgroundDrawable(ColorDrawable(0x00000000))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.35f }
        setContentView(R.layout.activity_resolving)
    }

    private fun ComponentActivity.showOpenError(uri: Uri) {
        Toast.makeText(
            this,
            getString(R.string.error_open_link, uri.toString()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun ComponentActivity.showResolveError() {
        Toast.makeText(this, R.string.error_resolve_short_link, Toast.LENGTH_LONG).show()
    }
}

internal fun Activity.launchRoutedViewIntent(
    uri: Uri,
    addNewTask: Boolean = false
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        putExtra(EXTRA_ROUTED, true)
        if (addNewTask) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    return runCatching { startActivity(intent) }.isSuccess
}

internal fun Activity.launchFallbackChooser(uri: Uri): Boolean {
    val base = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val candidates = packageManager.queryIntentActivities(base, 0)
    if (candidates.isEmpty()) return false
    val chooser = Intent.createChooser(base, null)
    return runCatching { startActivity(chooser) }.isSuccess
}
