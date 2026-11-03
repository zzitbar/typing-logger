package dev.local.typinglog

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 首页：顶部标签 + ViewPager2，可点标签也可左右滑动切换
 * 「微博 / 抖音 / 知乎」三个榜单。
 */
class HotSearchActivity : AppCompatActivity(), HotListFragment.Host {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pager: ViewPager2

    private val fragments = arrayOfNulls<HotListFragment>(HotSource.values().size)
    private var currentSource = HotSource.WEIBO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hot)

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        pager = findViewById(R.id.pager)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = HotSource.values().size

            override fun createFragment(position: Int): Fragment {
                val fragment = HotListFragment.newInstance(HotSource.values()[position])
                fragments[position] = fragment
                return fragment
            }
        }
        pager.offscreenPageLimit = 1

        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = when (HotSource.values()[position]) {
                HotSource.WEIBO -> "微博"
                HotSource.DOUYIN -> "抖音"
                HotSource.ZHIHU -> "知乎"
            }
        }.attach()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentSource = HotSource.values()[position]
                toolbar.title = titleOf(currentSource)
                toolbar.subtitle = fragments.getOrNull(position)?.subtitle.orEmpty()
                fragments.getOrNull(position)?.refreshIfStale()
            }
        })

        toolbar.inflateMenu(R.menu.menu_hot)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                fragments.getOrNull(pager.currentItem)?.refresh()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.btnHidden).setOnClickListener { askPassword() }

        currentSource = HotSource.values()[pager.currentItem]
        toolbar.title = titleOf(currentSource)
    }

    override fun onSubtitle(source: HotSource, text: String) {
        if (source == currentSource) toolbar.subtitle = text
    }

    private fun titleOf(source: HotSource): String = when (source) {
        HotSource.WEIBO -> "微博热搜"
        HotSource.DOUYIN -> "抖音热搜"
        HotSource.ZHIHU -> "知乎热搜"
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
                    Toast.makeText(
                        this@HotSearchActivity, "密码错误", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        dialog.show()
    }
}
