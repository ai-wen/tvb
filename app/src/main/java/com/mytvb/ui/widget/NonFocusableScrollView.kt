package com.mytvb.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

class NonFocusableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /**
     * 内容最大高度（像素）。ScrollView 原生不处理 android:maxHeight，
     * 由这里在 onMeasure 中钳制，超出的内容转为滚动。
     */
    var maxHeight: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight in 1 until Int.MAX_VALUE) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val newSpec = when (mode) {
                MeasureSpec.UNSPECIFIED ->
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                MeasureSpec.AT_MOST ->
                    MeasureSpec.makeMeasureSpec(minOf(maxHeight, size), MeasureSpec.AT_MOST)
                else -> heightMeasureSpec
            }
            super.onMeasure(widthMeasureSpec, newSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        // 自身不参与焦点搜索（避免 ScrollView 抢方向键焦点），
        // 但必须透传子项：清空实现会让滚动区内的按钮对 DPAD 焦点完全不可见。
        for (i in 0 until childCount) {
            getChildAt(i).addFocusables(views, direction, focusableMode)
        }
    }
}
