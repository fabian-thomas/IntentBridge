package de.fabianthomas.intentbridge

import android.content.Context
import android.widget.Toast

object CrossSpaceMessages {
    fun showForwardToast(context: Context, targetRole: ProfileRoleStore.Role) {
        val appCtx = context.applicationContext
        val roleLabel = ProfileRoleStore.describe(targetRole)
        Toast.makeText(appCtx, appCtx.getString(R.string.cross_space_sent_to, roleLabel), Toast.LENGTH_SHORT).show()
    }

    fun showOpenedToast(@Suppress("unused_parameter") context: Context) {
        // val appCtx = context.applicationContext
        // val currentRole = ProfileRoleStore.getRole(appCtx)
        // val roleLabel = ProfileRoleStore.describe(currentRole)
        // Toast.makeText(appCtx, appCtx.getString(R.string.cross_space_opened_here, roleLabel), Toast.LENGTH_SHORT).show()
    }
}
