package ltd.nextalone.pkginstallerplus.hook

import android.app.Activity
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.UserManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import ltd.nextalone.pkginstallerplus.HookEntry.injectModuleResources
import ltd.nextalone.pkginstallerplus.R
import ltd.nextalone.pkginstallerplus.utils.*

/**
 * Package Installer "v2" in its per-stage form (present in the LineageOS lineage-22.2, 23.0 and
 * 23.1 sources, i.e. Android 15 QPR2, Android 16 and Android 16 QPR1): InstallLaunch shows one
 * DialogFragment per stage, and the confirmation dialog reuses the old install_content_view layout.
 * It is only used when the platform's use_pia_v2 flag is on.
 *
 * Neither the classic hook ([InstallerHookQ], PackageInstallerActivity) nor the QPR2 hook
 * ([InstallerHookB], InstallationFragment) targets these classes, so on a device that uses this
 * variant the installer looks unchanged. The details are appended to the question text, which
 * avoids having to measure and overlay a view on top of a FrameLayout.
 */
object InstallerHookV2 {
    private const val TAG_DETAILS = "IPP_details"

    fun initOnce() {
        "$INSTALLER_V2_PKG.fragments.InstallConfirmationFragment".clazz?.method("onCreateDialog")?.hookAfter { param ->
            val dialog = param.result as? Dialog ?: return@hookAfter
            val activity = param.thisObject.hostActivity() ?: return@hookAfter
            injectModuleResources(activity.resources)
            // The dialog content only exists once the dialog is shown.
            dialog.setOnShowListener {
                try {
                    addInstallDetails(activity, dialog)
                } catch (e: Throwable) {
                    logError("addInstallDetails", e)
                }
            }
        }

        "$INSTALLER_V2_PKG.fragments.UninstallConfirmationFragment".clazz?.method("onCreateDialog")?.hookAfter { param ->
            val dialog = param.result as? Dialog ?: return@hookAfter
            val activity = param.thisObject.hostActivity() ?: return@hookAfter
            injectModuleResources(activity.resources)
            dialog.setOnShowListener {
                try {
                    addUninstallDetails(activity, dialog)
                } catch (e: Throwable) {
                    logError("addUninstallDetails", e)
                }
            }
        }
    }

    private fun Any.hostActivity(): Activity? =
        try {
            javaClass.getMethod("requireActivity").invoke(this) as? Activity
        } catch (e: Throwable) {
            null
        }

    private fun addInstallDetails(activity: Activity, dialog: Dialog) {
        val updateView: TextView? = dialog.findHostView("install_confirm_question_update")
        val newView: TextView? = dialog.findHostView("install_confirm_question")
        val question = (if (updateView?.visibility == View.VISIBLE) updateView else newView) ?: return
        if (question.tag == TAG_DETAILS) return

        val repository = activity.get("installRepository")
            ?: activity.fieldByType("model.InstallRepository")
            ?: return
        val newPkgInfo = (repository.get("newPackageInfo") as? PackageInfo)
            ?: (repository.fieldByType("android.content.pm.PackageInfo") as? PackageInfo)
            ?: return
        val userName = activity.getSystemService(UserManager::class.java)?.userName
        val oldPkgInfo = activity.packageManager.getPackageInfoOrNull(newPkgInfo.packageName)

        val sb = SpannableStringBuilder(question.text)
        sb.append("\n\n")
        val start = sb.length

        if (oldPkgInfo == null) {
            val newVersionStr = (newPkgInfo.versionName ?: "???") + "(" + newPkgInfo.compatLongVersionCode() + ")"
            val newSdkStr = newPkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???"

            sb.append(activity.getString(R.string.IPP_info_user) + ": ")
                .append(userName ?: "?")
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
                .append(userName ?: "?")
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

        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        question.text = sb
        question.tag = TAG_DETAILS
    }

    private fun addUninstallDetails(activity: Activity, dialog: Dialog) {
        // With app data the dialog uses uninstall_content_view (id "message"); without it the
        // text is the AlertDialog's own message view.
        val customMessage: TextView? = dialog.findHostView("message")
        val message = customMessage ?: dialog.findViewById<TextView>(android.R.id.message) ?: return
        if (message.tag == TAG_DETAILS) return

        val repository = activity.get("uninstallRepository")
            ?: activity.fieldByType("model.UninstallRepository")
            ?: return
        val packageName = (repository.get("targetPackageName") as? String)
            ?: (repository.fieldByType("android.content.pm.ApplicationInfo") as? ApplicationInfo)?.packageName
            ?: return
        val pkgInfo = activity.packageManager.getPackageInfoOrNull(packageName) ?: return

        val sb = SpannableStringBuilder(message.text)
        sb.append("\n\n")
        val start = sb.length

        sb.append(activity.getString(R.string.IPP_info_package) + ": ")
            .append(packageName)
            .append('\n')
            .append(activity.getString(R.string.IPP_info_version) + ": ")
            .append((pkgInfo.versionName ?: "???") + "(" + pkgInfo.compatLongVersionCode() + ")")
            .append('\n')
            .append(activity.getString(R.string.IPP_info_sdk) + ": ")
            .append(pkgInfo.applicationInfo?.targetSdkVersion?.toString() ?: "???")

        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        message.text = sb
        message.tag = TAG_DETAILS
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.compatLongVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
