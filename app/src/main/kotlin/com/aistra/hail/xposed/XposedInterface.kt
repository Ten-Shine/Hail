package com.aistra.hail.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import com.aistra.hail.BuildConfig
import com.aistra.hail.app.HailApi.ACTION_UNFREEZE
import com.aistra.hail.ui.api.ApiActivity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method

class XposedInterface : IXposedHookLoadPackage {
    @Throws(Throwable::class)
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (!loadPackageParam.isFirstApplication) {
            return
        }

        if (loadPackageParam.packageName != BuildConfig.APPLICATION_ID) {
            appFreezeInject(loadPackageParam)
        }
    }

    private fun appFreezeInject(loadPackageParam: LoadPackageParam) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hook = object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && param.args[0] != null) {
                        val intent = param.args[0] as Intent
                        var packageName = intent.getPackage()
                        val component = intent.component
                        if (packageName == null && component != null) {
                            packageName = component.packageName
                        }
                        val context = param.thisObject as Context
                        if (packageName != null && packageName != context.packageName) {
                            unfreezeApp(context, packageName)
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                Activity::class.java.name,
                loadPackageParam.classLoader,
                "startActivityForResult",
                Intent::class.java, Int::class.javaPrimitiveType,
                Bundle::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                TileService::class.java.name,
                loadPackageParam.classLoader,
                "startActivityAndCollapse",
                Intent::class.java, hook
            )
            val contextClass = XposedHelpers.findClass(
                ContextWrapper::class.java.name,
                loadPackageParam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                contextClass,
                "startActivity",
                Intent::class.java,
                hook
            )
            XposedHelpers.findAndHookMethod(
                contextClass,
                "startActivity",
                Intent::class.java, Bundle::class.java,
                hook
            )
        }
    }

    private fun unfreezeApp(context: Context, packageName: String) {
        val packageManager = context.applicationContext.packageManager
        val method: Method = packageManager.javaClass.getMethod(
            "isPackageSuspended",
            String::class.java
        )
        if (method.invoke(packageManager, packageName) as Boolean) {
            val intent = Intent(ACTION_UNFREEZE)
            intent.putExtra("package", packageName)
            context.startActivity(intent)

            /**
             * On my device, it took 560 milliseconds from [Context.startActivity] to [ApiActivity],
             * and another 10 milliseconds to successfully unfreeze.
             * This is a considerable delay, and I don't know how to optimize it.
             * We have to wait for Hail to successfully unfreeze it before launching it.
             *
             * Code awaiting optimization
             */
            Thread.sleep(300)
            repeat(6) {
                if (!(method.invoke(packageManager, packageName) as Boolean)) return@repeat
                Thread.sleep(75)
            }
        }
    }
}
