package ltd.nextalone.pkginstallerplus.hook

import android.app.Activity
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.UserManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ltd.nextalone.pkginstallerplus.HookEntry.injectModuleResources
import ltd.nextalone.pkginstallerplus.R
import ltd.nextalone.pkginstallerplus.utils.*

private const val TAG_INSTALL_DETAILS = "IPP_install_details"
private const val TAG_UNINSTALL_DETAILS = "IPP_uninstall_details"

/**
 * Package Installer "v2" as found in the Android 16 QPR2 sources (LineageOS lineage-23.2): a
 * single InstallationFragment / UninstallationFragment whose updateUI() is re-run on every stage
 * change. The earlier per-stage variant (InstallConfirmationFragment etc., present in the
 * lineage-22.2, 23.0 and 23.1 sources) is handled by [InstallerHookV2].
 */
object InstallerHookB {
    fun initOnce() {
        "$INSTALLER_V2_PKG.fragments.InstallationFragment".clazz?.method("updateUI")?.hookAfter {
            val fragment = it.thisObject
            val dialog = fragment.get("mDialog") as? Dialog ?: return@hookAfter

            val activity =
                fragment.javaClass.getMethod("requireActivity").invoke(fragment) as? Activity
                    ?: return@hookAfter

            if (isUserActionRequired(activity)) {
                injectModuleResources(activity.resources)
                addInstallDetails(activity, dialog)
            } else {
                removeInstallDetails(dialog)
            }
        }

        "$INSTALLER_V2_PKG.fragments.UninstallationFragment".clazz?.method("updateUI")?.hookAfter {
            val fragment = it.thisObject
            val dialog = fragment.get("mDialog") as? Dialog ?: return@hookAfter

            val activity =
                fragment.javaClass.getMethod("requireActivity").invoke(fragment) as? Activity
                    ?: return@hookAfter
            injectModuleResources(activity.resources)
            addUninstallDetails(activity, dialog)
        }
    }

    private fun findInstallViewModel(activity: Activity): Any? =
        activity.get("installViewModel") ?: activity.fieldByType("viewmodel.InstallViewModel")

    /**
     * True while the install confirmation (or another "user action required") dialog is shown.
     *
     * The stage is read through the public getters (getCurrentInstallStage().getValue()) first,
     * because they are part of the API and survive field renames; the private LiveData fields
     * are only a fallback.
     */
    private fun isUserActionRequired(activity: Activity): Boolean {
        val viewModel = findInstallViewModel(activity) ?: return false
        val liveData = viewModel.callOrNull("getCurrentInstallStage")
            ?: listOf("_currentInstallStage", "currentInstallStage")
                .firstNotNullOfOrNull { key -> viewModel.get(key) }
            ?: return false
        val stage = liveData.callOrNull("getValue") ?: liveData.get("mData") ?: liveData.get("data")
        return stage?.javaClass?.simpleName == "InstallUserActionRequired"
    }

    private fun addInstallDetails(
        activity: Activity,
        dialog: Dialog,
    ) {
        val appSnippet: ViewGroup = dialog.findHostView("app_snippet") ?: return
        val parent = appSnippet.parent as? ViewGroup ?: return

        if (parent.findViewWithTag<TextView>(TAG_INSTALL_DETAILS) != null) return

        val viewModel = findInstallViewModel(activity) ?: return
        val repository = viewModel.get("repository")
            ?: viewModel.fieldByType("model.InstallRepository")
            ?: return
        val newPkgInfo = (repository.get("newPackageInfo") as? PackageInfo)
            ?: (repository.fieldByType("android.content.pm.PackageInfo") as? PackageInfo)
            ?: return
        val usrManager = (repository.get("userManager") as? UserManager)
            ?: activity.getSystemService(UserManager::class.java)
            ?: return

        val oldPkgInfo = try {
            activity.packageManager.getPackageInfoOrNull(newPkgInfo.packageName)
        } catch (e: Exception) {
            null
        }

        val sb = SpannableStringBuilder()
        if (oldPkgInfo == null) {
            val newVersionStr = (newPkgInfo.versionName ?: "???") + "(" + newPkgInfo.compatLongVersionCode() + ")"
            val newSdkStr = newPkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???"

            sb.append(activity.getString(R.string.IPP_info_user) + ": ")
                .append(usrManager.userName)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_package) + ": ")
                .append(newPkgInfo.packageName)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_version) + ": ")
                .append(newVersionStr)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_sdk) + ": ")
                .append(newSdkStr)
        } else {
            val oldVersionStr = "${oldPkgInfo.versionName ?: "???"}(${oldPkgInfo.compatLongVersionCode()})"
            val newVersionStr = "${newPkgInfo.versionName ?: "???"}(${newPkgInfo.compatLongVersionCode()})"
            val oldSdkStr = oldPkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???"
            val newSdkStr = newPkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???"

            sb.append(activity.getString(R.string.IPP_info_user) + ": ")
                .append(usrManager.userName)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_package) + ": ")
                .append(newPkgInfo.packageName)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_version1) + ": ")
                .append(oldVersionStr)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_version2) + ": ")
                .append(newVersionStr, ForegroundColorSpan(ThemeUtil.colorGreen), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_sdk) + ": ")
                .append(oldSdkStr)
                .append(" ➞ ")
                .append(newSdkStr, ForegroundColorSpan(ThemeUtil.colorGreen), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        parent.addView(
            TextView(activity).apply {
                tag = TAG_INSTALL_DETAILS
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                text = sb
            },
        )
    }

    private fun removeInstallDetails(dialog: Dialog) {
        val appSnippet: ViewGroup = dialog.findHostView("app_snippet") ?: return
        val parent = appSnippet.parent as? ViewGroup ?: return
        parent.findViewWithTag<View>(TAG_INSTALL_DETAILS)?.let { parent.removeView(it) }
    }

    private fun addUninstallDetails(
        activity: Activity,
        dialog: Dialog,
    ) {
        val appSnippet: ViewGroup = dialog.findHostView("app_snippet") ?: return
        val parent = appSnippet.parent as? ViewGroup ?: return

        if (parent.findViewWithTag<TextView>(TAG_UNINSTALL_DETAILS) != null) return

        val viewModel = activity.get("uninstallViewModel")
            ?: activity.fieldByType("viewmodel.UninstallViewModel")
            ?: return
        val repository = viewModel.get("repository")
            ?: viewModel.fieldByType("model.UninstallRepository")
            ?: return
        val packageName = (repository.get("targetPackageName") as? String)
            ?: (repository.fieldByType("android.content.pm.ApplicationInfo") as? ApplicationInfo)?.packageName
            ?: return

        val pkgInfo = try {
            activity.packageManager.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        if (pkgInfo != null) {
            val oldVersionStr = (pkgInfo.versionName ?: "???") + "(" + pkgInfo.compatLongVersionCode() + ")"
            val oldSdkStr = pkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???"

            val sb = SpannableStringBuilder()
            sb.append(activity.getString(R.string.IPP_info_package) + ": ")
                .append(packageName)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_version) + ": ")
                .append(oldVersionStr)
                .append('\n')
                .append(activity.getString(R.string.IPP_info_sdk) + ": ")
                .append(oldSdkStr)

            parent.addView(
                TextView(activity).apply {
                    tag = TAG_UNINSTALL_DETAILS
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                    text = sb
                },
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.compatLongVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
