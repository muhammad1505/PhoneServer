package dev.pocketshare.runtime

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pocketshare.R
import java.util.Locale

/**
 * One snapshot of the system rules that can stop the tunnel while the screen is off.
 * Values that the current Android version does not expose stay `null` so the settings
 * screen hides those rows instead of showing a false warning.
 */
data class BackgroundAccessStatus(
    val batteryOptimizationIgnored: Boolean,
    val backgroundRestricted: Boolean?,
    val dataSaverRestricted: Boolean?,
    val notificationsEnabled: Boolean,
    val notificationPermissionRequired: Boolean,
) {
    val needsAttention: Boolean
        get() =
            !batteryOptimizationIgnored ||
                backgroundRestricted == true ||
                dataSaverRestricted == true ||
                !notificationsEnabled
}

/** A settings screen to try, newest/OEM-specific first. */
data class BackgroundAccessTarget(
    @param:StringRes val labelRes: Int,
    val intent: Intent,
)

enum class OemFamily {
    XIAOMI,
    HUAWEI,
    HONOR,
    COLOR_OS,
    ONEPLUS,
    VIVO,
    SAMSUNG,
    ASUS,
    TRANSSION,
    NOKIA,
    MEIZU,
    LETV,
    LENOVO,
    OTHER,
}

/**
 * Reads the Android/OEM background policy and builds the matching settings screens.
 * Intents are returned in priority order instead of being resolved with PackageManager:
 * Android 11 package visibility can hide OEM components, and component names differ
 * between ROM versions, so the UI starts them one by one and keeps the first that opens.
 */
class BackgroundAccess(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val deviceFamily = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.US)
    private val family = detectFamily()

    fun status(): BackgroundAccessStatus {
        val batteryOptimizationIgnored =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appContext
                    .getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(packageName) == true
            } else {
                // Battery optimization does not exist below Android 6.
                true
            }

        val backgroundRestricted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appContext.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
            } else {
                null
            }

        val dataSaverRestricted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (
                    appContext
                        .getSystemService(ConnectivityManager::class.java)
                        ?.restrictBackgroundStatus
                ) {
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> true
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                    -> false

                    else -> null
                }
            } else {
                null
            }

        val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val notificationPermissionRequired =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

        return BackgroundAccessStatus(
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            backgroundRestricted = backgroundRestricted,
            dataSaverRestricted = dataSaverRestricted,
            notificationsEnabled = notificationsEnabled,
            notificationPermissionRequired = notificationPermissionRequired,
        )
    }

    /**
     * Battery screens. The direct "ignore battery optimizations" dialog needs the
     * restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Play
     * Protect can treat as unsafe for sideloaded apps. Opening the optimization
     * list needs no permission, so the user selects the app there instead.
     */
    fun batteryTargets(): List<BackgroundAccessTarget> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    BackgroundAccessTarget(
                        labelRes = R.string.background_label_battery_optimization,
                        intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    ),
                )
            }
            addAll(oemBatteryTargets())
            add(BackgroundAccessTarget(R.string.background_label_app_info, appDetailsIntent()))
        }

    /**
     * OEM autostart managers, then generic battery optimization and app info.
     * Only the current device family is added so unrelated components are not
     * probed with failed intents.
     */
    fun autoStartTargets(): List<BackgroundAccessTarget> =
        buildList {
            addAll(oemAutoStartTargets())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    BackgroundAccessTarget(
                        labelRes = R.string.background_label_battery_optimization,
                        intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    ),
                )
            }
            add(BackgroundAccessTarget(R.string.background_label_app_info, appDetailsIntent()))
        }

    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            appDetailsIntent()
        }

    /**
     * The Data Saver screen uses a framework action that is hidden from the public
     * SDK, so it is addressed by its stable string and backed by the public data
     * usage screen. Calling apps cannot grant the exemption themselves; the user
     * turns it off on the screen that opens.
     */
    fun dataSaverTargets(): List<BackgroundAccessTarget> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(
                    BackgroundAccessTarget(
                        labelRes = R.string.background_label_data_saver,
                        intent = Intent("android.settings.DATA_SAVER_SETTINGS"),
                    ),
                )
                add(
                    BackgroundAccessTarget(
                        labelRes = R.string.background_label_data_usage,
                        intent = Intent("android.settings.DATA_USAGE_SETTINGS"),
                    ),
                )
            }
            add(BackgroundAccessTarget(R.string.background_label_app_info, appDetailsIntent()))
        }

    fun appDetailsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )

    /** Name shown on the autostart button so the user knows which app to open. */
    @StringRes
    fun autoStartLabelRes(): Int =
        when (family) {
            OemFamily.XIAOMI -> R.string.oem_miui_security
            OemFamily.HUAWEI -> R.string.oem_phone_manager
            OemFamily.HONOR -> R.string.oem_honor_manager
            OemFamily.COLOR_OS -> R.string.oem_coloros
            OemFamily.ONEPLUS -> R.string.oem_oxygenos
            OemFamily.VIVO -> R.string.oem_imanager
            OemFamily.SAMSUNG -> R.string.oem_device_care
            OemFamily.ASUS -> R.string.oem_mobile_manager
            OemFamily.TRANSSION -> R.string.oem_phone_master
            OemFamily.NOKIA -> R.string.oem_power_saver
            OemFamily.MEIZU, OemFamily.LENOVO -> R.string.oem_security
            OemFamily.LETV -> R.string.oem_letv_safe
            OemFamily.OTHER -> R.string.background_label_app_info
        }

    /** Short OEM-specific steps, with a general fallback for other devices. */
    @StringRes
    fun guideRes(): Int =
        when (family) {
            OemFamily.XIAOMI -> R.string.background_guide_xiaomi
            OemFamily.HUAWEI -> R.string.background_guide_huawei
            OemFamily.HONOR -> R.string.background_guide_honor
            OemFamily.COLOR_OS, OemFamily.ONEPLUS -> R.string.background_guide_coloros
            OemFamily.VIVO -> R.string.background_guide_vivo
            OemFamily.SAMSUNG -> R.string.background_guide_samsung
            OemFamily.ASUS -> R.string.background_guide_asus
            OemFamily.TRANSSION -> R.string.background_guide_transsion
            OemFamily.NOKIA -> R.string.background_guide_nokia
            OemFamily.MEIZU -> R.string.background_guide_meizu
            OemFamily.LETV -> R.string.background_guide_letv
            OemFamily.LENOVO -> R.string.background_guide_lenovo
            OemFamily.OTHER -> R.string.background_guide_generic
        }

    private fun oemBatteryTargets(): List<BackgroundAccessTarget> =
        buildList {
            if (family == OemFamily.SAMSUNG) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_device_care,
                        packageId = "com.samsung.android.lool",
                        className = "com.samsung.android.sm.ui.battery.BatteryActivity",
                    ),
                )
            }
            if (family == OemFamily.HUAWEI || family == OemFamily.HONOR) {
                add(
                    componentTarget(
                        labelRes = R.string.background_label_power_manager,
                        packageId = "com.huawei.systemmanager",
                        className = "com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
                    ),
                )
            }
        }

    private fun oemAutoStartTargets(): List<BackgroundAccessTarget> =
        buildList {
            if (family == OemFamily.XIAOMI) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_miui_security,
                        packageId = "com.miui.securitycenter",
                        className = "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                )
            }
            if (family == OemFamily.HUAWEI) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_phone_manager,
                        packageId = "com.huawei.systemmanager",
                        className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                )
                add(
                    componentTarget(
                        labelRes = R.string.oem_phone_manager,
                        packageId = "com.huawei.systemmanager",
                        className = "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    ),
                )
            }
            if (family == OemFamily.HONOR) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_honor_manager,
                        packageId = "com.hihonor.systemmanager",
                        className = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                )
            }
            if (family == OemFamily.COLOR_OS || family == OemFamily.ONEPLUS) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_coloros,
                        packageId = "com.coloros.safecenter",
                        className = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                )
                add(
                    componentTarget(
                        labelRes = R.string.oem_coloros,
                        packageId = "com.coloros.oppoguardelf",
                        className = "com.coloros.oppoguardelf.permission.startup.StartupAppListActivity",
                    ),
                )
                add(
                    componentTarget(
                        labelRes = R.string.oem_coloros,
                        packageId = "com.oppo.safe",
                        className = "com.oppo.safe.permission.startup.StartupAppListActivity",
                    ),
                )
            }
            if (family == OemFamily.ONEPLUS) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_oxygenos,
                        packageId = "com.oneplus.security",
                        className = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    ),
                )
            }
            if (family == OemFamily.VIVO) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_imanager,
                        packageId = "com.vivo.permissionmanager",
                        className = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    ),
                )
                add(
                    componentTarget(
                        labelRes = R.string.oem_imanager,
                        packageId = "com.iqoo.secure",
                        className = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    ),
                )
            }
            if (family == OemFamily.SAMSUNG) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_device_care,
                        packageId = "com.samsung.android.lool",
                        className = "com.samsung.android.sm.ui.battery.BatteryActivity",
                    ),
                )
            }
            if (family == OemFamily.ASUS) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_mobile_manager,
                        packageId = "com.asus.mobilemanager",
                        className = "com.asus.mobilemanager.powersaver.PowerSaverSettings",
                    ),
                )
                add(
                    componentTarget(
                        labelRes = R.string.oem_mobile_manager,
                        packageId = "com.asus.mobilemanager",
                        className = "com.asus.mobilemanager.MainActivity",
                    ),
                )
            }
            if (family == OemFamily.NOKIA) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_power_saver,
                        packageId = "com.evenwell.powersaving.g3",
                        className = "com.evenwell.powersaving.g3.exceptionlist.ExceptionListActivity",
                    ),
                )
            }
            if (family == OemFamily.LETV) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_letv_safe,
                        packageId = "com.letv.android.letvsafe",
                        className = "com.letv.android.letvsafe.AutobootManageActivity",
                    ),
                )
            }
            if (family == OemFamily.MEIZU) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_security,
                        packageId = "com.meizu.safe",
                        className = "com.meizu.safe.security.SecureMainActivity",
                    ),
                )
            }
            if (family == OemFamily.LENOVO) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_security,
                        packageId = "com.lenovo.security",
                        className = "com.lenovo.security.purebackground.PureBackgroundActivity",
                    ),
                )
            }
            if (family == OemFamily.TRANSSION) {
                add(
                    componentTarget(
                        labelRes = R.string.oem_phone_master,
                        packageId = "com.transsion.phonemaster",
                        className = "com.transsion.phonemaster.ui.autostart.AutoStartActivity",
                    ),
                )
            }
        }

    private fun componentTarget(
        @StringRes labelRes: Int,
        packageId: String,
        className: String,
    ): BackgroundAccessTarget =
        BackgroundAccessTarget(
            labelRes = labelRes,
            intent = Intent().setComponent(ComponentName(packageId, className)),
        )

    private fun detectFamily(): OemFamily =
        when {
            matches("xiaomi", "redmi", "poco") -> OemFamily.XIAOMI
            matches("huawei") -> OemFamily.HUAWEI
            matches("honor") -> OemFamily.HONOR
            matches("oppo", "realme") -> OemFamily.COLOR_OS
            matches("oneplus") -> OemFamily.ONEPLUS
            matches("vivo", "iqoo") -> OemFamily.VIVO
            matches("samsung") -> OemFamily.SAMSUNG
            matches("asus") -> OemFamily.ASUS
            matches("nokia", "hmd") -> OemFamily.NOKIA
            matches("meizu") -> OemFamily.MEIZU
            matches("letv") -> OemFamily.LETV
            matches("lenovo") -> OemFamily.LENOVO
            matches("infinix", "tecno", "itel") -> OemFamily.TRANSSION
            else -> OemFamily.OTHER
        }

    private fun matches(vararg keys: String): Boolean = keys.any { key -> key in deviceFamily }
}
