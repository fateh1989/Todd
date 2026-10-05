package com.todd.core.project

import android.content.Context

class ActiveProjectStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY_ACTIVE_PROJECT, null)
        ?.takeIf { it.isNotBlank() }

    fun set(projectId: String?) {
        prefs.edit().apply {
            if (projectId.isNullOrBlank()) {
                remove(KEY_ACTIVE_PROJECT)
            } else {
                putString(KEY_ACTIVE_PROJECT, projectId)
            }
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "todd_project_selection"
        private const val KEY_ACTIVE_PROJECT = "active_project_id"
    }
}
