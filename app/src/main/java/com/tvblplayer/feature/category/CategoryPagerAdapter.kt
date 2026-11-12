package com.tvblplayer.feature.category

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tvblplayer.core.log.AppLog
import com.tvblplayer.core.model.Zone
import com.tvblplayer.feature.video.VideoGridFragment
import android.os.SystemClock

class CategoryPagerAdapter(
    fragment: Fragment,
    private val zones: List<Zone>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = zones.size

    override fun createFragment(position: Int): Fragment {
        val zone = zones[position]
        AppLog.d(
            "Category",
            "createFragment pos=$position title=${zone.title} rid=${zone.rid} t=${SystemClock.uptimeMillis()}",
        )
        return if (zone.rid == null) {
            VideoGridFragment.newPopular()
        } else {
            VideoGridFragment.newRegion(zone.rid)
        }
    }

    override fun getItemId(position: Int): Long = CategoryZones.stableKeyFor(zones[position]).hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = zones.any { CategoryZones.stableKeyFor(it).hashCode().toLong() == itemId }
}
