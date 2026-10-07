package com.mytvb.feature.playlist

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 节目单首页：主界面默认内容页，只显示 tv.json 节目单内容。
 *
 * - 条目 URL 为 B 站视频/合集链接（BV 号）→ 原生播放器
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

    private fun onEntryClick(vod: com.mytvb.feature.marmot.domain.MarmotModels.Vod) {
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

/** 节目单列表适配器：分组标题 + 节目条目（item 视图程序化构建，减少资源文件）。 */
private class PlaylistAdapter(
    private val onEntryClick: (com.mytvb.feature.marmot.domain.MarmotModels.Vod) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class SectionRow(val live: com.mytvb.feature.marmot.domain.MarmotModels.Live) : Row
        data class EntryRow(val vod: com.mytvb.feature.marmot.domain.MarmotModels.Vod) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(lives: List<com.mytvb.feature.marmot.domain.MarmotModels.Live>) {
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

    private fun createEntryView(parent: ViewGroup): TextView {
        return AppCompatTextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 64)
            ).apply {
                setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            }
            textSizeSp(18f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(context, R.drawable.tab_round_background)
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
    }

    private class SectionHolder(view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(live: com.mytvb.feature.marmot.domain.MarmotModels.Live) {
            (itemView as TextView).text = live.name.ifBlank { live.tag }
        }
    }

    private class EntryHolder(view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(
            vod: com.mytvb.feature.marmot.domain.MarmotModels.Vod,
            click: (com.mytvb.feature.marmot.domain.MarmotModels.Vod) -> Unit
        ) {
            val textView = itemView as TextView
            textView.text = vod.name
            val playable = vod.url.isNotBlank()
            textView.isEnabled = playable
            textView.isFocusable = playable
            textView.isClickable = playable
            textView.alpha = if (playable) 1f else 0.45f
            textView.setOnClickListener { click(vod) }
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
