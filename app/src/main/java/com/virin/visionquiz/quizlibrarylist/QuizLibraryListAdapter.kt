package com.virin.visionquiz.quizlibrarylist

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.databinding.ItemQuizLibraryBinding
import com.virin.visionquiz.util.dp


class QuizLibraryListAdapter constructor(
    private val buttonClickListener: OnButtonClickListener,
    private val selectionListener: SelectionListener
) : ListAdapter<QuizLibrary, QuizLibraryListAdapter.ViewHolder>(DiffCallback()) {

    private val selectedItemIds = HashSet<Int>()
    var isSelectionMode = false
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemQuizLibraryBinding.inflate(inflater, parent, false)

        return ViewHolder(parent.context, binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val quizLibrary = getItem(position)
        holder.bind(quizLibrary, buttonClickListener)

        holder.binding.cardView.isChecked = selectedItemIds.contains(quizLibrary.id)

        holder.binding.cameraBtn.isEnabled = isSelectionMode.not()
        holder.binding.screenRecordBtn.isEnabled = isSelectionMode.not()
        holder.binding.moreBtn.isEnabled = isSelectionMode.not()
    }

    inner class ViewHolder(
        val context: Context, val binding: ItemQuizLibraryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(quizLibrary: QuizLibrary, listener: OnButtonClickListener) {
            binding.cardView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(quizLibrary)
                } else {
                    listener.onButtonClicked(quizLibrary, ROOT_VIEW)
                }
            }
            binding.cardView.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode()
                    toggleSelection(quizLibrary)
                }
                true
            }
            binding.cameraBtn.setOnClickListener {
                listener.onButtonClicked(quizLibrary, CAMERA_BUTTON)
            }
            binding.screenRecordBtn.setOnClickListener {
                listener.onButtonClicked(quizLibrary, SCREEN_RECORD_BUTTON)
            }
            binding.moreBtn.setOnClickListener {
                showListPopupWindow(it, listener, quizLibrary)
            }

            binding.quizLibrary = quizLibrary
//            binding.name.text = quizLibrary.name
//            binding.quizCount.text = binding.root.context.getString(R.string.quiz_count, quizLibrary.quizCount)
        }

        private fun showListPopupWindow(
            view: View, listener: OnButtonClickListener, quizLibrary: QuizLibrary
        ) {
            val items: List<ListPopupItem> = listOf(
                ListPopupItem(
                    context.resources.getString(R.string.accessibility_search_button),
                    R.drawable.icon_accessible_forward_24px,
                    action = ACCESSIBILITY_SEARCH_BUTTON
                ),
                ListPopupItem.Divider,
                ListPopupItem(
                    context.resources.getString(R.string.rename),
                    R.drawable.round_edit_24,
                    action = RENAME_BUTTON
                ),
                ListPopupItem(
                    context.resources.getString(R.string.delete),
                    R.drawable.round_delete_24,
                    MaterialColors.getColor(context, R.attr.colorError, Color.BLACK),
                    action = DELETE_BUTTON
                ),
            )
            val listPopupWindow = ListPopupWindow(context, null, R.attr.listPopupWindowStyle)
            listPopupWindow.setAdapter(
//                ArrayAdapter(context, R.layout.list_popup_window_item, items)
                ListPopupIconAdapter(context, items)
            )
            listPopupWindow.anchorView = view
            listPopupWindow.width = 200.dp//ListPopupWindow.WRAP_CONTENT
            listPopupWindow.height = ListPopupWindow.WRAP_CONTENT
            listPopupWindow.setOnItemClickListener { parent, view, pos, id ->
                items[pos].action?.let { action -> listener.onButtonClicked(quizLibrary, action) }
                listPopupWindow.dismiss()
            }
            listPopupWindow.show()


//            val menuBuilder = popup.menu as MenuBuilder
//            menuBuilder.setOptionalIconsVisible(true)
//            for (item in menuBuilder.visibleItems) {
//                val iconMarginPx =
//                    TypedValue.applyDimension(
//                        TypedValue.COMPLEX_UNIT_DIP, ICON_MARGIN.toFloat(), resources.displayMetrics)
//                        .toInt()
//                if (item.icon != null) {
//                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
//                        item.icon = InsetDrawable(item.icon, iconMarginPx, 0, iconMarginPx,0)
//                    } else {
//                        item.icon =
//                            object : InsetDrawable(item.icon, iconMarginPx, 0, iconMarginPx, 0) {
//                                override fun getIntrinsicWidth(): Int {
//                                    return intrinsicHeight + iconMarginPx + iconMarginPx
//                                }
//                            }
//                    }
//                }
        }


        private fun showPopupMenu(
            view: View,
            listener: OnButtonClickListener,
            quizLibrary: QuizLibrary
        ) {
//            val ctxWrapper = ContextThemeWrapper(view.context, R.style.Widget_Material3_PopupMenu_ListPopupWindow)
            val popup = PopupMenu(context, view)
            popup.menuInflater.inflate(R.menu.quiz_item_menu, popup.menu)
//            val popup = LongPressPopupMenu(view.context, view, R.menu.quiz_library_item_menu)
            popup.setOnMenuItemClickListener { menuItem ->
                // Respond to menu item click.
                when (menuItem.itemId) {
                    R.id.rename -> listener.onButtonClicked(quizLibrary, RENAME_BUTTON)
                    R.id.delete -> listener.onButtonClicked(quizLibrary, DELETE_BUTTON)
                }
                true
            }
//            popup.setOnDismissListener {
//                // Respond to popup being dismissed.
//            }
            // Show the popup menu.
            popup.show()


        }
    }

    private fun toggleSelection(quizLibrary: QuizLibrary) {
        if (selectedItemIds.contains(quizLibrary.id)) {
            selectedItemIds.remove(quizLibrary.id)
        } else {
            selectedItemIds.add(quizLibrary.id)
        }
        val position = currentList.indexOfFirst { it.id == quizLibrary.id }
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position)
        }

        selectionListener.onSelectionChanged(selectedItemIds)
    }

    fun enterSelectionMode() {
        if (isSelectionMode.not()) {
            isSelectionMode = true
//            notifyDataSetChanged()
            notifyItemRangeChanged(0, itemCount)
            selectionListener.onEnterSelection()
        }
    }

    fun exitSelectionMode() {
        if (isSelectionMode) {
            isSelectionMode = false
            clearSelection()
            selectionListener.onExitSelection()
        }
    }

    private fun clearSelection() {
        selectedItemIds.clear()
//        notifyDataSetChanged()
        notifyItemRangeChanged(0, itemCount)
    }

    fun getSelectedLibraries(): List<QuizLibrary> =
        currentList.filter { it.id in selectedItemIds }

    private class DiffCallback : DiffUtil.ItemCallback<QuizLibrary>() {
        override fun areItemsTheSame(
            oldItem: QuizLibrary, newItem: QuizLibrary
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: QuizLibrary, newItem: QuizLibrary
        ): Boolean {
            return oldItem == newItem
        }
    }

    // 创建一个自定义适配器
    private class ListPopupIconAdapter(
        private val context: Context, val items: List<ListPopupItem>
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): ListPopupItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean = !items[position].isDivider

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            if (item.isDivider) {
                return createDividerView(parent)
            }

            val view = if (convertView?.id == R.id.list_popup_item_text) {
                convertView
            } else {
                inflater.inflate(R.layout.list_popup_window_item, parent, false)
            }
            val textView = view.findViewById<View>(R.id.list_popup_item_text) as TextView
            val color = item.color ?: MaterialColors.getColor(
                context, R.attr.colorOnBackground, Color.BLACK
            )
            item.icon?.let { it ->
                val drawable = AppCompatResources.getDrawable(context, it)
                drawable?.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
                textView.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
            }
            textView.text = item.title
            textView.setTextColor(color)
            return view
        }

        private fun createDividerView(parent: ViewGroup): View {
            return FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    9.dp
                )
                setPadding(16.dp, 4.dp, 16.dp, 4.dp)
                addView(
                    View(parent.context).apply {
                        setBackgroundColor(
                            MaterialColors.getColor(
                                parent,
                                R.attr.colorOutlineVariant,
                                Color.LTGRAY
                            )
                        )
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1.dp
                        )
                    }
                )
            }
        }
    }

    data class ListPopupItem(
        val title: String,
        @DrawableRes val icon: Int? = null,
        @ColorInt val color: Int? = null,
        val action: Int? = null,
        val isDivider: Boolean = false
    ) {
        companion object {
            val Divider = ListPopupItem("", isDivider = true)
        }
    }


    interface OnButtonClickListener {
        fun onButtonClicked(quizLibrary: QuizLibrary, btnType: Int)

        fun onButtonLongClicked(quizLibrary: QuizLibrary)
    }

    interface SelectionListener {
        fun onEnterSelection()
        fun onSelectionChanged(selectedItems: HashSet<Int>)
        fun onExitSelection()
    }

    companion object {
        const val ROOT_VIEW = 0
        const val CAMERA_BUTTON = 1
        const val SCREEN_RECORD_BUTTON = 2
        const val RENAME_BUTTON = 3
        const val DELETE_BUTTON = 4
        const val ACCESSIBILITY_SEARCH_BUTTON = 5
    }
}
