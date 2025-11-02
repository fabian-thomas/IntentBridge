package de.fabianthomas.intentbridge

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

object ProfileRoleStore {

    enum class Role {
        PRIVATE_SPACE,
        PERSONAL_SPACE
    }

    private const val PREFS = "intentbridge_profile_role"
    private const val KEY_ROLE = "role"

    fun getRole(context: Context): Role {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_ROLE, null)
        val resolved = stored?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            ?: defaultRole(context)
        if (stored == null) {
            setRole(context, resolved)
        }
        return resolved
    }

    fun setRole(context: Context, role: Role) {
        prefs(context).edit().putString(KEY_ROLE, role.name).apply()
    }

    fun listeningPort(role: Role): Int = when (role) {
        Role.PRIVATE_SPACE -> PRIVATE_LISTEN_PORT
        Role.PERSONAL_SPACE -> PERSONAL_LISTEN_PORT
    }

    fun targetPort(role: Role): Int = listeningPort(opposite(role))

    fun opposite(role: Role): Role = when (role) {
        Role.PRIVATE_SPACE -> Role.PERSONAL_SPACE
        Role.PERSONAL_SPACE -> Role.PRIVATE_SPACE
    }

    fun describe(role: Role): String = when (role) {
        Role.PRIVATE_SPACE -> "Private Space"
        Role.PERSONAL_SPACE -> "Personal Space"
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun defaultRole(context: Context): Role {
        val userManager = context.applicationContext.getSystemService(UserManager::class.java)
        val isManaged = userManager?.isManagedProfile == true
        return if (isManaged) Role.PRIVATE_SPACE else Role.PERSONAL_SPACE
    }

    private const val PERSONAL_LISTEN_PORT = 39123
    private const val PRIVATE_LISTEN_PORT = 39124
}
