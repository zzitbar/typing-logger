package dev.local.typinglog

import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 首页：微博热搜榜。
 * 点条目跳微博（优先客户端，装不了就开网页）；右下角小圆点是日志入口。
 */
class HotSearchActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView

    private val items = ArrayList<HotItem>()
    private val adapter = HotAdapter()

    private var loading = false
    private var lastLoadedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hot)

        toolbar = findViewById(R.id.toolbar)
        swipe = findViewById(R.id.swipe)
        recycler = findViewById(R.id.rvHot)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        toolbar.inflateMenu(R.menu.menu_hot)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                refresh()
                true
            } else {
                false
            }
        }

        swipe.setColorSchemeColors(0xFFFF8200.toInt())
        swipe.setOnRefreshListener { refresh() }

        findViewById<View>(R.id.btnHidden).setOnClickListener { askPassword() }

        restoreCache()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!loading && lastLoadedAt > 0 &&
            System.currentTimeMillis() - lastLoadedAt > 5 * 60_000
        ) {
            refresh()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 数据

    private fun restoreCache() {
        val cached = HotSearchRepository.loadCache(this) ?: return
        val (list, at) = cached
        if (list.isEmpty()) return
        items.clear()
        items.addAll(list)
        adapter.notifyDataSetChanged()
        toolbar.subtitle = "缓存 " + timeText(at)
    }

    private fun refresh() {
        if (loading) return
        loading = true
        swipe.isRefreshing = true
        toolbar.subtitle = "加载中…"

        io.execute {
            val list = try {
                HotSearchRepository.load()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                swipe.isRefreshing = false

                if (list.isNotEmpty()) {
                    items.clear()
                    items.addAll(list)
                    adapter.notifyDataSetChanged()
                    lastLoadedAt = System.currentTimeMillis()
                    HotSearchRepository.saveCache(this, list)
                    toolbar.subtitle = "更新于 " + timeText(lastLoadedAt)
                    recycler.scrollToPosition(0)
                } else {
                    toolbar.subtitle =
                        if (items.isEmpty()) "加载失败，下拉重试" else "刷新失败，显示缓存"
                }
            }
        }
    }

    // ------------------------------------------------------------ 跳转

    private fun openWeibo(title: String) {
        val q = Uri.encode(title)
        val url = "https://m.weibo.cn/search?containerid=100103type%3D1%26q%3D$q"
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_TITLE, title)
        )
    }

    // ------------------------------------------------------------ 隐藏入口

    private fun askPassword() {
        val input = EditText(this).apply {
            hint = "请输入密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("访问日志")
            .setView(box)
            .setPositiveButton("进入", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (Passwords.matches(this@HotSearchActivity, input.text.toString())) {
                    val nonce = Auth.grant()
                    startActivity(
                        Intent(this@HotSearchActivity, LogsActivity::class.java)
                            .putExtra(LogsActivity.EXTRA_AUTH, nonce)
                    )
                    dialog.dismiss()
                } else {
                    input.setText("")
                    toast("密码错误")
                }
            }
        }
        dialog.show()
    }

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
            holder.itemView.setOnClickListener { openWeibo(item.title) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun rankColor(rank: Int): Int = when (rank) {
        1 -> 0xFFE6162D.toInt()
        2 -> 0xFFFF6A00.toInt()
        3 -> 0xFFFFA000.toInt()
        else -> 0xFFBDBDBD.toInt()
    }

    private fun timeText(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
