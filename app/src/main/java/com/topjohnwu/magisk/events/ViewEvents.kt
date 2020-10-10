package com.topjohnwu.magisk.events

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.navigation.NavDirections
import com.topjohnwu.magisk.MainDirections
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.*
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.base.ActivityResultCallback
import com.topjohnwu.magisk.core.base.BaseActivity
import com.topjohnwu.magisk.core.model.module.Repo
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.magisk.view.MarkDownWindow
import com.topjohnwu.magisk.view.Shortcuts
import kotlinx.coroutines.launch

class ViewActionEvent(val action: BaseActivity.() -> Unit) : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) = action(activity)
}

class OpenChangelogEvent(val item: Repo) : ViewEventWithScope(), ContextExecutor {
    override fun invoke(context: Context) {
        scope.launch {
            MarkDownWindow.show(context, null, item::readme)
        }
    }
}

class PermissionEvent(
    private val permission: String,
    private val callback: (Boolean) -> Unit
) : ViewEvent(), ActivityExecutor {

    override fun invoke(activity: BaseUIActivity<*, *>) =
        activity.withPermission(permission) {
            onSuccess {
                callback(true)
            }
            onFailure {
                callback(false)
            }
        }
}

class BackPressEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        activity.onBackPressed()
    }
}

class DieEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        activity.finish()
    }
}

class ShowUIEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        activity.setContentView()
    }
}

class RecreateEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        activity.recreate()
    }
}

class MagiskInstallFileEvent(private val callback: ActivityResultCallback)
    : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
        try {
            activity.startActivityForResult(intent, Const.ID.SELECT_FILE, callback)
            Utils.toast(R.string.patch_file_msg, Toast.LENGTH_LONG)
        } catch (e: ActivityNotFoundException) {
            Utils.toast(R.string.app_not_found, Toast.LENGTH_SHORT)
        }
    }
}

class NavigationEvent(
    private val directions: NavDirections
) : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: BaseUIActivity<*, *>) {
        (activity as? BaseUIActivity<*, *>)?.apply {
            directions.navigate()
        }
    }
}

class AddHomeIconEvent : ViewEvent(), ContextExecutor {
    override fun invoke(context: Context) {
        Shortcuts.addHomeIcon(context)
    }
}

class SelectModuleEvent : ViewEvent(), FragmentExecutor {
    override fun invoke(fragment: BaseUIFragment<*, *>) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("application/zip")
        try {
            fragment.apply {
                activity.startActivityForResult(intent, Const.ID.FETCH_ZIP) { code, intent ->
                    if (code == Activity.RESULT_OK && intent != null) {
                        intent.data?.also {
                            MainDirections.actionFlashFragment(it, Const.Value.FLASH_ZIP).navigate()
                        }
                    }
                }
            }
        } catch (e: ActivityNotFoundException) {
            Utils.toast(R.string.app_not_found, Toast.LENGTH_SHORT)
        }
    }
}
