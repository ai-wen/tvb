package com.mytvb.feature.playlist

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.ext.toast
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.feature.marmot.domain.MarmotModels.Live
import com.mytvb.feature.marmot.domain.MarmotModels.Vod
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 节目单首页：主界面默认内容页，只显示 tv.json 节目单内容。
 *
 * - 条目 URL 为 B 站视频/合集链接（BV 号）→ 原生播放器
 * - 条目带 pic 字段 → 显示封面图（ImageLoader）
 * - 条目无 URL（暂无片源）→ 置灰不可点
 * - 其它类型链接 → 提示暂不支持
 */
class PlaylistHomeFragment : Fragment(), MainTabFocusTarget {

    companion object {
        fun newInstance(): PlaylistHomeFragment = PlaylistHomeFragment()

        /** 从 B 站视频/合集链接提取 BV 号。 */
        private val BILI_VIDEO_REGEX = Regex("""bilibili\.com/video/(BV[0-9A-Za-z]{10})""")
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = PlaylistAdapter(::onEntryClick)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlist_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.playlist_list)
        emptyView = view.findViewById(R.id.playlist_empty)
        val manager = GridLayoutManager(requireContext(), 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.getItemViewType(position) == PlaylistAdapter.TYPE_SECTION) 4 else 1
                }
            }
        }
        recyclerView.layoutManager = manager
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            PlaylistRepository.playlist.collect { list ->
                adapter.submit(list)
                emptyView.isVisible = list.all { it.vods.isEmpty() }
            }
        }

        // 启动即触发：先展示缓存，下载完成后通过 Flow 自动刷新
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            PlaylistRepository.ensureFresh(appContext)
        }
    }

    private fun onEntryClick(vod: Vod) {
        val bvid = BILI_VIDEO_REGEX.find(vod.url)?.groupValues?.getOrNull(1)
        when {
            !bvid.isNullOrBlank() -> VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = VideoModel(bvid = bvid, title = vod.name)
            )

            vod.url.isBlank() -> requireContext().toast(getString(R.string.playlist_no_source))

            else -> requireContext().toast(getString(R.string.playlist_unsupported_link))
        }
    }

    override fun focusEntryFromMainTab(): Boolean {
        if (!::recyclerView.isInitialized) return false
        val child = recyclerView.findFocus() ?: recyclerView.getChildAt(0)
        return child?.requestFocus() == true
    }
}

/** 节目单列表适配器：分组标题 + 图文节目卡片（item 视图程序化构建，减少资源文件）。 */
private class PlaylistAdapter(
    private val onEntryClick: (Vod) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class SectionRow(val live: Live) : Row
        data class EntryRow(val vod: Vod) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(lives: List<Live>) {
        rows = lives.flatMap { live ->
            if (live.vods.isEmpty()) {
                emptyList()
            } else {
                listOf<Row>(Row.SectionRow(live)) + live.vods.map { Row.EntryRow(it) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.SectionRow) TYPE_SECTION else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_SECTION) {
            SectionHolder(createSectionView(parent))
        } else {
            EntryHolder(createEntryView(parent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.SectionRow -> (holder as SectionHolder).bind(row.live)
            is Row.EntryRow -> (holder as EntryHolder).bind(row.vod) { onEntryClick(it) }
        }
    }

    private fun createSectionView(parent: ViewGroup): TextView {
        return AppCompatTextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSizeSp(20f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(context, 8)
            val padV = dp(context, 12)
            setPadding(padH, padV, padH, padV)
        }
    }

    /** 图文卡片：封面（16:9 区域）+ 单行标题，焦点高亮复用 tab_round_background 选择器。 */
    private fun createEntryView(parent: ViewGroup): View {
        val context = parent.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            }
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(context, R.drawable.tab_round_background)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        }
        val cover = AppCompatImageView(context).apply {
            id = android.R.id.icon
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 92)
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            // 常驻占位底：pic 缺失时直接露出；有图时先露出（加载中状态）后被封面覆盖
            setBackgroundResource(R.drawable.playlist_cover_placeholder)
        }
        val title = AppCompatTextView(context).apply {
            id = android.R.id.text1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
            textSizeSp(16f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            gravity = Gravity.CENTER
        }
        container.addView(cover)
        container.addView(title)
        return container
    }

    private class SectionHolder(view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(live: Live) {
            (itemView as TextView).text = live.name.ifBlank { live.tag }
        }
    }

    private class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: AppCompatImageView = view.findViewById(android.R.id.icon)
        private val title: TextView = view.findViewById(android.R.id.text1)

        fun bind(vod: Vod, click: (Vod) -> Unit) {
            title.text = vod.name
            if (vod.pic.isNotBlank()) {
                ImageLoader.load(cover, vod.pic)
            } else {
                cover.setImageDrawable(null)
            }
            val playable = vod.url.isNotBlank()
            itemView.isFocusable = playable
            itemView.isClickable = playable
            itemView.alpha = if (playable) 1f else 0.45f
            itemView.setOnClickListener { click(vod) }
        }
    }

    private fun TextView.textSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(context: android.content.Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        const val TYPE_SECTION = 0
        const val TYPE_ENTRY = 1
    }
}
