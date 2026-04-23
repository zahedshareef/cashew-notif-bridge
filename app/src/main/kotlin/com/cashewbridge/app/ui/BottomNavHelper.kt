package com.cashewbridge.app.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.cashewbridge.app.R
import com.google.android.material.bottomnavigation.BottomNavigationView

object BottomNavHelper {

    fun setup(activity: Activity, bottomNav: BottomNavigationView, selectedId: Int) {
        bottomNav.menu.findItem(selectedId)?.isChecked = true

        val handler = { itemId: Int ->
            when (itemId) {
                R.id.nav_home -> switchTo(activity, MainActivity::class.java)
                R.id.nav_notifications -> switchTo(activity, NotificationsActivity::class.java)
                R.id.nav_insights -> switchTo(activity, InsightsActivity::class.java)
                R.id.nav_more -> showMoreMenu(activity, bottomNav)
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedId) {
                // Reselecting More always re-opens the popup; other tabs are no-op.
                if (item.itemId == R.id.nav_more) showMoreMenu(activity, bottomNav)
                return@setOnItemSelectedListener true
            }
            handler(item.itemId)
            false
        }
        bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_more) showMoreMenu(activity, bottomNav)
        }
    }

    private fun switchTo(activity: Activity, target: Class<*>) {
        if (activity.javaClass == target) return
        val intent = Intent(activity, target).addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun showMoreMenu(activity: Activity, bottomNav: BottomNavigationView) {
        val anchor: View = bottomNav.findViewById(R.id.nav_more) ?: bottomNav
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, R.string.manage_rules)
            menu.add(0, 2, 1, R.string.view_blocklist)
            menu.add(0, 3, 2, R.string.view_logs)
            setOnMenuItemClickListener { item ->
                val target = when (item.itemId) {
                    1 -> RulesActivity::class.java
                    2 -> AppBlocklistActivity::class.java
                    3 -> LogsActivity::class.java
                    else -> return@setOnMenuItemClickListener false
                }
                activity.startActivity(Intent(activity, target))
                true
            }
            show()
        }
    }
}
