package dev.local.typinglog

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 单个榜单页，由 ViewPager2 承载，可左右滑动切换。
 * 每个页各自缓存、各自刷新。
 */
class HotListFragment : Fragment() {

    interface Host {
        fun onSubtitle(source: HotSource, text: String)
    }

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var empty: View

    private val items = ArrayList<HotItem>()
    private val adapter = HotAdapter()

    private var source = HotSource.WEIBO
    private var lastLoadedAt = 0L
    private var loadSeq = 0

    /** 当前页要显示的副标题，切页时由 Activity 取用。 */
    var subtitle: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = requireArguments().getString(ARG_SOURCE) ?: HotSource.WEIBO.name
        source = try {
            HotSource.valueOf(name)
        } catch (_: Exception) {
            HotSource.WEIBO
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_hot, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipe = view.findViewById(R.id.swipe)
        recycler = view.findViewById(R.id.rvHot)
        empty = view.findViewById(R.id.empty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        swipe.setColorSchemeColors(0xFFFF8200.toInt())
        swipe.setOnRefreshListener { refresh() }

        restoreCache()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 对外

    fun refresh() {
        if (!isAdded || view == null || !::swipe.isInitialized) return

        val seq = ++loadSeq
        val requested = source
        setSubtitle("加载中…")
        swipe.isRefreshing = true

        io.execute {
            val list = try {
                HotSearchRepository.load(requested)
            } catch (_: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (seq != loadSeq) return@runOnUiThread

                swipe.isRefreshing = false
                if (list.isNotEmpty()) {
                    submit(list)
                    lastLoadedAt = System.currentTimeMillis()
                    HotSearchRepository.saveCache(requireContext(), requested, list)
                    setSubtitle("更新于 " + timeText(lastLoadedAt))
                    recycler.scrollToPosition(0)
                } else {
                    setSubtitle(
                        if (items.isEmpty()) "加载失败，下拉重试" else "刷新失败，显示缓存"
                    )
                    updateEmpty()
                }
            }
        }
    }

    fun refreshIfStale() {
        if (lastLoadedAt > 0 &&
            System.currentTimeMillis() - lastLoadedAt > 5 * 60_000
        ) {
            refresh()
        }
    }

    // ------------------------------------------------------------ 内部

    private fun restoreCache() {
        val cached = HotSearchRepository.loadCache(requireContext(), source) ?: return
        val (list, at) = cached
        if (list.isEmpty()) return
        submit(list)
        setSubtitle("缓存 " + timeText(at))
    }

    /** DiffUtil 更新，刷新时只动变化的行，不会整屏闪一下。 */
    private fun submit(list: List<HotItem>) {
        val old = items.toList()
        items.clear()
        items.addAll(list)

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].title == items[newPos].title

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos] == items[newPos]
        }).dispatchUpdatesTo(adapter)

        updateEmpty()
    }

    private fun updateEmpty() {
        if (!::empty.isInitialized) return
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setSubtitle(text: String) {
        subtitle = text
        (activity as? Host)?.onSubtitle(source, text)
    }

    private fun openItem(item: HotItem) {
        val q = Uri.encode(item.title)
        val url = when (source) {
            HotSource.WEIBO -> "https://m.weibo.cn/search?containerid=100103type%3D1%26q%3D$q"
            HotSource.DOUYIN -> "https://www.douyin.com/search/$q"
            HotSource.ZHIHU ->
                if (item.url.isNotBlank()) item.url
                else "https://www.zhihu.com/search?type=content&q=$q"
        }
        startActivity(
            Intent(requireContext(), WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_TITLE, item.title)
        )
    }

    private fun timeText(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

    // ------------------------------------------------------------ 列表

    private inner class HotAdapter : RecyclerView.Adapter<HotAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val rank: TextView = view.findViewById(R.id.tvRank)
            val title: TextView = view.findViewById(R.id.tvTitle)
            val hot: TextView = view.findViewById(R.id.tvHot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_hot, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.rank.text = item.rank.toString()
            holder.rank.backgroundTintList = ColorStateList.valueOf(rankColor(item.rank))
            holder.title.text = item.title
            holder.hot.text = item.hot
            holder.itemView.setOnClickListener { openItem(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun rankColor(rank: Int): Int = when (rank) {
        1 -> 0xFFE6162D.toInt()
        2 -> 0xFFFF6A00.toInt()
        3 -> 0xFFFFA000.toInt()
        else -> 0xFFBDBDBD.toInt()
    }

    companion object {
        private const val ARG_SOURCE = "source"

        fun newInstance(source: HotSource): HotListFragment = HotListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, source.name) }
        }
    }
}
