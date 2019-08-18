package jp.juggler.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import jp.juggler.wlclient.R
import java.util.*

class ActionsDialog {

    private val actionList = ArrayList<Action>()

    private class Action internal constructor(
        internal val caption: CharSequence,
        internal val r: () -> Unit
    )

    fun addAction(caption: CharSequence, r: () -> Unit): ActionsDialog {
        actionList.add(Action(caption, r))
        return this
    }

    fun show(context: Context, title: CharSequence? = null): ActionsDialog {

        AlertDialog.Builder(context).apply {

            if (title?.isNotEmpty() == true) setTitle(title)

            val captionList = actionList.map{ it.caption }.toTypedArray()
            setItems(captionList) { _, which ->
                if (which >= 0 && which < actionList.size) {
                    actionList[which].r()
                }
            }

            setNegativeButton(R.string.cancel, null)

            show()
        }

        return this
    }
}
