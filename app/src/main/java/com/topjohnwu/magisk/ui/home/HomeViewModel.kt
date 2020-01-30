package com.topjohnwu.magisk.ui.home

import android.Manifest
import android.os.Build
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.download.RemoteFileService
import com.topjohnwu.magisk.core.model.MagiskJson
import com.topjohnwu.magisk.core.model.ManagerJson
import com.topjohnwu.magisk.core.model.UpdateInfo
import com.topjohnwu.magisk.data.repository.MagiskRepository
import com.topjohnwu.magisk.extensions.*
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject.Magisk
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject.Manager
import com.topjohnwu.magisk.model.entity.recycler.DeveloperItem
import com.topjohnwu.magisk.model.entity.recycler.HomeItem
import com.topjohnwu.magisk.model.events.OpenInappLinkEvent
import com.topjohnwu.magisk.model.events.dialog.EnvFixDialog
import com.topjohnwu.magisk.model.events.dialog.ManagerInstallDialog
import com.topjohnwu.magisk.model.events.dialog.UninstallDialog
import com.topjohnwu.magisk.model.navigation.Navigation
import com.topjohnwu.magisk.model.observer.Observer
import com.topjohnwu.magisk.ui.base.BaseViewModel
import com.topjohnwu.magisk.ui.base.itemBindingOf
import com.topjohnwu.magisk.utils.KObservableField
import com.topjohnwu.superuser.Shell
import me.tatarka.bindingcollectionadapter2.BR
import kotlin.math.roundToInt

enum class MagiskState {
    NOT_INSTALLED, UP_TO_DATE, OBSOLETE, LOADING
}

class HomeViewModel(
    private val repoMagisk: MagiskRepository
) : BaseViewModel() {

    val isNoticeVisible = KObservableField(Config.safetyNotice)

    val stateMagisk = KObservableField(MagiskState.LOADING)
    val stateManager = KObservableField(MagiskState.LOADING)
    val stateTextMagisk = Observer(stateMagisk) {
        when (stateMagisk.value) {
            MagiskState.NOT_INSTALLED -> R.string.installed_error.res()
            MagiskState.UP_TO_DATE -> R.string.up_to_date.res()
            MagiskState.LOADING -> R.string.loading.res()
            MagiskState.OBSOLETE -> R.string.obsolete.res()
        }
    }
    val stateTextManager = Observer(stateManager) {
        when (stateManager.value) {
            MagiskState.NOT_INSTALLED -> R.string.channel_error.res()
            MagiskState.UP_TO_DATE -> R.string.up_to_date.res()
            MagiskState.LOADING -> R.string.loading.res()
            MagiskState.OBSOLETE -> R.string.obsolete.res()
        }
    }
    val statePackageManager = packageName
    val statePackageOriginal = statePackageManager == BuildConfig.APPLICATION_ID
    val stateVersionUpdateMagisk = KObservableField("")
    val stateVersionUpdateManager = KObservableField("")

    val stateMagiskProgress = KObservableField(0)
    val stateManagerProgress = KObservableField(0)

    val stateHideManagerName = R.string.manager.res().let {
        if (!statePackageOriginal) {
            it.replaceRandomWithSpecial(3)
        } else {
            it
        }
    }

    val items = listOf(DeveloperItem.Mainline, DeveloperItem.App, DeveloperItem.Project)
    val itemBinding = itemBindingOf<HomeItem> {
        it.bindExtra(BR.viewModel, this)
    }
    val itemDeveloperBinding = itemBindingOf<DeveloperItem> {
        it.bindExtra(BR.viewModel, this)
    }

    private var shownDialog = false

    init {
        RemoteFileService.progressBroadcast.observeForever {
            when (it?.second) {
                is Magisk.Download,
                is Magisk.Flash -> stateMagiskProgress.value = it.first.times(100f).roundToInt()
                is Manager -> stateManagerProgress.value = it.first.times(100f).roundToInt()
            }
        }
    }

    override fun refresh() = repoMagisk.fetchUpdate()
        .onErrorReturn { Info.remote }
        .subscribeK { updateBy(it) }

    private fun updateBy(info: UpdateInfo) {
        stateMagisk.value = when {
            !info.magisk.isInstalled -> MagiskState.NOT_INSTALLED
            info.magisk.isObsolete -> MagiskState.OBSOLETE
            else -> MagiskState.UP_TO_DATE
        }

        stateManager.value = when {
            !info.app.isUpdateChannelCorrect && isConnected.value -> MagiskState.NOT_INSTALLED
            info.app.isObsolete -> MagiskState.OBSOLETE
            else -> MagiskState.UP_TO_DATE
        }

        stateVersionUpdateMagisk.value = when {
            info.magisk.isObsolete -> "%s > %s".format(
                Info.env.magiskVersionString.clipVersion(info.magisk.version),
                info.magisk.version.clipVersion(Info.env.magiskVersionString)
            )
            else -> ""
        }

        stateVersionUpdateManager.value = when {
            info.app.isObsolete -> "%s > %s".format(
                BuildConfig.VERSION_NAME.clipVersion(info.app.version),
                info.app.version.clipVersion(BuildConfig.VERSION_NAME)
            )
            else -> ""
        }

        ensureEnv()
    }

    fun onLinkPressed(link: String) = OpenInappLinkEvent(link).publish()

    fun onDeletePressed() = UninstallDialog().publish()

    fun onManagerPressed() = ManagerInstallDialog().publish()

    fun onMagiskPressed() = withPermissions(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ).map { check(it);it }
        .subscribeK { Navigation.install().publish() }
        .add()

    fun toggle(kof: KObservableField<Boolean>) = kof.toggle()

    fun hideNotice() {
        Config.safetyNotice = false
        isNoticeVisible.value = false
    }

    private fun ensureEnv() {
        val invalidStates = listOf(
            MagiskState.NOT_INSTALLED,
            MagiskState.LOADING
        )

        // Don't bother checking env when magisk is not installed, loading or already has been shown
        if (
            invalidStates.any { it == stateMagisk.value } ||
            shownDialog ||
            // don't care for emulators either
            Build.DEVICE.orEmpty().contains("generic") ||
            Build.PRODUCT.orEmpty().contains("generic")
        ) {
            return
        }

        Shell.su("env_check")
            .toSingle()
            .map { it.exec() }
            .filter { !it.isSuccess }
            .subscribeK {
                shownDialog = true
                EnvFixDialog().publish()
            }
    }

    private fun String.clipVersion(other: String = ""): String {
        val thisVersion = substringBefore('-')
        val otherVersion = other.substringBefore('-')
        return if (thisVersion != otherVersion) thisVersion else substringAfter('-')
    }

}

@Suppress("unused")
val MagiskJson.isInstalled
    get() = Info.env.magiskVersionCode > 0
val MagiskJson.isObsolete
    get() = Info.env.magiskVersionCode < versionCode && isInstalled
val ManagerJson.isUpdateChannelCorrect
    get() = versionCode > 0
val ManagerJson.isObsolete
    get() = BuildConfig.VERSION_CODE < versionCode
