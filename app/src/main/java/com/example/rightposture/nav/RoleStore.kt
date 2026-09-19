package com.example.rightposture.nav

import android.content.Context

/**
 * Remembers which role this device is being used as.
 *
 * SharedPreferences rather than Room: this is one enum, it is not clinical data, and it must be
 * readable before the database is opened so the first frame can render the right screen.
 *
 * 7.3 makes this a property of the signed-in account instead. Until then the choice is per-device,
 * which is honest for a demo where the physio and the patient are the same person with one phone -
 * and is why [clear] exists at all.
 */
class RoleStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("rightposture.role", Context.MODE_PRIVATE)

    fun role(): Role? = preferences.getString(KEY, null)?.let { stored ->
        // An unrecognised value means a downgrade or a hand-edited file. Treating it as "no role"
        // sends the user through the picker again, which is recoverable; crashing is not.
        Role.entries.firstOrNull { it.name == stored }
    }

    fun setRole(role: Role) = preferences.edit().putString(KEY, role.name).apply()

    fun clear() = preferences.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "role"
    }
}
