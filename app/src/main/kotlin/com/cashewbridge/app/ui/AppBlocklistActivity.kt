package com.cashewbridge.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityBlocklistBinding
import com.cashewbridge.app.model.AppBlocklistEntry
import com.cashewbridge.app.model.AppDatabase
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #9 — App-level blocklist screen.
 * Lists every app that has sent a notification through the service.
 * Toggling an app ON blocks all future notifications from it.
 */
class AppBlocklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: BlocklistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BottomNavHelper.setup(this, binding.bottomNav.root, R.id.nav_more)

        db = AppDatabase.getInstance(this)
        adapter = BlocklistAdapter(
            onBlock = { entry -> lifecycleScope.launch(Dispatchers.IO) { db.appBlocklistDao().insert(entry) } },
            onUnblock = { pkg -> lifecycleScope.launch(Dispatchers.IO) { db.appBlocklistDao().remove(pkg) } }
        )
        binding.rvBlocklist.layoutManager = LinearLayoutManager(this)
        binding.rvBlocklist.adapter = adapter

        loadApps()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadApps() {
        lifecycleScope.launch {
            // Collect distinct apps seen in the notification cache
            val cachedPackages = withContext(Dispatchers.IO) {
                db.cachedNotificationDao().getDistinctPackages()
            }
            val blockedPackages = withContext(Dispatchers.IO) {
                db.appBlocklistDao().getAllPackageNames().toSet()
            }
            val pm = packageManager
            val items = cachedPackages.map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) { pkg }
                val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                BlocklistItem(
                    packageName = pkg,
                    appLabel = label,
                    isBlocked = pkg in blockedPackages,
                    icon = icon
                )
            }.sortedBy { it.appLabel }

            binding.tvEmptyBlocklist.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(items)
        }
    }

    data class BlocklistItem(
        val packageName: String,
        val appLabel: String,
        val isBlocked: Boolean,
        val icon: android.graphics.drawable.Drawable?
    )

    class BlocklistAdapter(
        private val onBlock: (AppBlocklistEntry) -> Unit,
        private val onUnblock: (String) -> Unit
    ) : ListAdapter<BlocklistItem, BlocklistAdapter.VH>(DIFF) {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_app_icon)
            val label: TextView = v.findViewById(R.id.tv_app_label)
            val pkg: TextView = v.findViewById(R.id.tv_app_package)
            val toggle: MaterialSwitch = v.findViewById(R.id.switch_block)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_blocklist_app, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = currentList[position]
            holder.label.text = item.appLabel
            holder.pkg.text = item.packageName
            item.icon?.let { holder.icon.setImageDrawable(it) }
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = item.isBlocked
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                val updated = item.copy(isBlocked = checked)
                val newList = currentList.toMutableList().also { it[position] = updated }
                submitList(newList)
                if (checked) onBlock(AppBlocklistEntry(item.packageName, item.appLabel))
                else onUnblock(item.packageName)
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<BlocklistItem>() {
                override fun areItemsTheSame(a: BlocklistItem, b: BlocklistItem) =
                    a.packageName == b.packageName
                override fun areContentsTheSame(a: BlocklistItem, b: BlocklistItem) = a == b
            }
        }
    }
}
