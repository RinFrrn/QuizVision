import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import java.util.*
import kotlin.concurrent.schedule

class RenameDialogFragment(
    val title: String,
    val placeholder: String,
    val listener: RenameDialogListener
) : DialogFragment() {

    //    private lateinit var listener: RenameDialogListener
    private var editText: EditText? = null

    interface RenameDialogListener {
        fun onDialogPositiveClick(newName: String)
    }

    fun show(manager: FragmentManager) {
        this.show(manager, TAG)
    }

//    override fun onAttach(context: Context) {
//        super.onAttach(context)
//        try {
//            listener = context as RenameDialogListener
//        } catch (e: ClassCastException) {
//            throw ClassCastException("$context must implement RenameDialogListener")
//        }
//    }

    override fun onResume() {
        super.onResume()

        editText?.requestFocus()
        editText?.selectAll()
        Timer().schedule(200) {
            editText?.let { showSoftKeyboard(it) }
        }
    }

    private fun showSoftKeyboard(view: View) {
        if (isResumed) {
            val inputMethodManager =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        // Create a linear layout for the dialog
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
//            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Create a text view for the dialog message
        val message = EditText(context).apply {
            hint = placeholder
            setText(placeholder)
            // 设置IME Action
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            setRawInputType(InputType.TYPE_CLASS_TEXT)
//            setTextColor(Color.BLACK)
//            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnEditorActionListener { textView, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val newName = textView.text.toString()
                    if (newName.isNotEmpty()) {
                        dialog?.dismiss()
                        listener.onDialogPositiveClick(newName)
                    }
                    true
                } else {
                    false
                }
            }
        }
        editText = message
        layout.addView(message)

        // Create the dialog
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(R.string.confirm) { dialog, _ ->
                val newName = message.text.toString()
                // Do something with the new name
                if (newName.isNotEmpty()) {
                    listener.onDialogPositiveClick(newName)
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
//            .apply {
//                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
//            }
    }

    companion object {
        const val TAG = "RenameDialogFragment"
    }

//    /**
//     * use case
//     */
//    override fun onDialogPositiveClick(newName: String) {
//        // 在这里编写重命名文件的代码
//        Toast.makeText(this, "文件已重命名为：$newName", Toast.LENGTH_SHORT).show()
//    }
}
