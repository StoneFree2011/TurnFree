package ru.stonefree.vkwg

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import ru.stonefree.vkwg.config.TurnFreeInstalledApp
import ru.stonefree.vkwg.databinding.ItemSplitTunnelAppBinding
import java.util.Locale

class TurnFreeSplitTunnelAppAdapter(
    context: Context,
    private val allApps: List<TurnFreeInstalledApp>,
    initialSelection: Set<String>,
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val selectedPackages = initialSelection.toMutableSet()
    private var visibleApps: List<TurnFreeInstalledApp> = allApps

    override fun getCount(): Int = visibleApps.size

    override fun getItem(position: Int): TurnFreeInstalledApp = visibleApps[position]

    override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) {
            ItemSplitTunnelAppBinding.inflate(inflater, parent, false)
        } else {
            ItemSplitTunnelAppBinding.bind(convertView)
        }
        val app = getItem(position)
        binding.appIconView.setImageDrawable(app.icon)
        binding.appNameText.text = app.label
        binding.appPackageText.text = app.packageName
        binding.appCheckBox.isClickable = false
        binding.appCheckBox.isFocusable = false
        binding.appCheckBox.isChecked = app.packageName in selectedPackages
        return binding.root
    }

    fun updateQuery(rawQuery: String) {
        val query = rawQuery.trim().lowercase(Locale.getDefault())
        visibleApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        val packageName = getItem(position).packageName
        if (packageName in selectedPackages) {
            selectedPackages -= packageName
        } else {
            selectedPackages += packageName
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedPackages.clear()
        notifyDataSetChanged()
    }

    fun currentSelection(): Set<String> = selectedPackages.toSet()
}
