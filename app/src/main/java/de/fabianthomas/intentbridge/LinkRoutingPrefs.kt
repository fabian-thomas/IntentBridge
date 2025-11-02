package de.fabianthomas.intentbridge

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONObject

object LinkRoutingPrefs {
    private const val PREF_NAME = "link_routing_prefs"

    enum class LinkCategory(
        val storageKey: String,
        @StringRes val labelRes: Int
    ) {
        BROWSER("browser", R.string.routing_category_browser),
        MAPS("maps", R.string.routing_category_maps),
        YOUTUBE("youtube", R.string.routing_category_youtube),
        MAIL("mail", R.string.routing_category_mail),
        TEL("tel", R.string.routing_category_tel);

        companion object {
            fun fromKey(key: String?): LinkCategory? =
                values().firstOrNull { it.storageKey == key }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun preferredRole(context: Context, category: LinkCategory): ProfileRoleStore.Role {
        val stored = prefs(context).getString(keyFor(category), null)
        return stored?.let { runCatching { ProfileRoleStore.Role.valueOf(it) }.getOrNull() }
            ?: defaultRole(category)
    }

    fun setPreferredRole(
        context: Context,
        category: LinkCategory,
        role: ProfileRoleStore.Role
    ) {
        prefs(context).edit().putString(keyFor(category), role.name).apply()
    }

    fun applyRemoteUpdate(
        context: Context,
        categoryKey: String?,
        roleName: String?
    ): Boolean {
        val category = LinkCategory.fromKey(categoryKey) ?: return false
        val role = roleName?.let { runCatching { ProfileRoleStore.Role.valueOf(it) }.getOrNull() }
            ?: return false
        setPreferredRole(context, category, role)
        return true
    }

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        LinkCategory.values().forEach { category ->
            put(category.storageKey, preferredRole(context, category).name)
        }
    }

    fun applySnapshot(context: Context, json: JSONObject?): Boolean {
        if (json == null) return false
        var applied = false
        LinkCategory.values().forEach { category ->
            val roleName = json.optString(category.storageKey)
            if (!roleName.isNullOrEmpty()) {
                runCatching { ProfileRoleStore.Role.valueOf(roleName) }
                    .onSuccess {
                        setPreferredRole(context, category, it)
                        applied = true
                    }
            }
        }
        return applied
    }

    private fun keyFor(category: LinkCategory): String = "preferred_${category.storageKey}"

    private fun defaultRole(category: LinkCategory): ProfileRoleStore.Role = when (category) {
        LinkCategory.BROWSER -> ProfileRoleStore.Role.PERSONAL_SPACE
        LinkCategory.MAPS -> ProfileRoleStore.Role.PRIVATE_SPACE
        LinkCategory.YOUTUBE -> ProfileRoleStore.Role.PERSONAL_SPACE
        LinkCategory.MAIL -> ProfileRoleStore.Role.PERSONAL_SPACE
        LinkCategory.TEL -> ProfileRoleStore.Role.PERSONAL_SPACE
    }
}
