package com.tvblplayer.ext.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvblplayer.ext.data.FavEntry
import com.tvblplayer.ext.data.FavStore
import com.tvblplayer.databinding.ActivityFavoritesBinding
import com.tvblplayer.databinding.ItemCardPosterBinding
import com.tvblplayer.ext.util.FocusMotion
import com.tvblplayer.ext.util.ImageLoader

/**
 * 收藏夹。海报网格，长按取消收藏。
 */
class FavoritesActivity : BaseTvActivity() {

    private lateinit var b: ActivityFavoritesBinding
    private lateinit var store: FavStore
    private lateinit var adapter: FavAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(b.root)

        ImageLoader.init(cacheDir)
        store = FavStore(this)

        adapter = FavAdapter(
            onClick = { open(it) },
            onLongClick = { entry ->
                store.remove(entry.url)
                Toast.makeText(this, "已取消收藏「${entry.title}」", Toast.LENGTH_SHORT).show()
                refresh()
            },
        )
        b.grid.adapter = adapter
        b.grid.itemAnimator = null

        // 按可用宽度算列数，避免固定列数在不同分辨率下留大缝
        b.grid.post {
            val availableDp = b.grid.width / resources.displayMetrics.density
            val span = (availableDp / 220f).toInt().coerceIn(3, 9)
            b.grid.layoutManager = GridLayoutManager(this, span)
            refresh()
        }
    }

    private fun refresh() {
        val list = store.all()
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isNotEmpty()) {
            b.grid.post { b.grid.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun open(entry: FavEntry) {
        MediaLauncher.open(this, entry.url, entry.title, entry.image)
    }

    private class FavAdapter(
        private val onClick: (FavEntry) -> Unit,
        private val onLongClick: (FavEntry) -> Unit,
    ) : RecyclerView.Adapter<FavAdapter.VH>() {

        private var items: List<FavEntry> = emptyList()

        fun submit(list: List<FavEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCardPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            FocusMotion.attach(b.cardRoot, focusedScale = 1.08f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemCardPosterBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(entry: FavEntry) {
                b.caption.text = entry.title
                if (entry.image != null) {
                    b.placeholder.visibility = View.GONE
                    b.poster.visibility = View.VISIBLE
                    ImageLoader.load(b.poster, entry.image)
                } else {
                    b.poster.visibility = View.GONE
                    b.placeholder.visibility = View.VISIBLE
                    b.placeholder.text = entry.title
                }
                b.cardRoot.setOnClickListener { onClick(entry) }
                b.cardRoot.setOnLongClickListener {
                    onLongClick(entry)
                    true
                }
            }
        }
    }
}
