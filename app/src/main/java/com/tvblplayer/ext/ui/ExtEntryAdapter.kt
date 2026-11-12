package com.tvblplayer.ext.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tvblplayer.databinding.ItemExtEntryBinding

/** ext 入口列表适配器，复用 blbl 的 item_setting_entry 视觉。 */
class ExtEntryAdapter(
    private val onClick: (ExtEntry) -> Unit,
) : RecyclerView.Adapter<ExtEntryAdapter.Vh>() {

    private val items = ArrayList<ExtEntry>()

    fun submit(list: List<ExtEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemExtEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemExtEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ExtEntry, onClick: (ExtEntry) -> Unit) {
            binding.tvTitle.text = item.title
            binding.tvDesc.text = item.subtitle
            binding.tvDesc.visibility = if (item.subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
