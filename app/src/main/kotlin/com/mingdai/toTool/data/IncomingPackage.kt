package com.mingdai.toTool.data

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 别的应用通过「打开方式」送进来的安装包（.apks / .xapk / .apkm）。
 *
 * MainActivity 收到 ACTION_VIEW 后把文件的 Uri 放进来，同时让导航层跳到「安装特殊应用」；
 * 那个页面读到以后立刻取走并清空，所以这里不会残留旧文件。
 * 用全局对象而不是沿着 导航 → 路由 → 页面 一层层传参，是因为这个值只有“收到文件”这一个入口、一个消费者。
 */
object IncomingPackage {
    var uri by mutableStateOf<Uri?>(null)
}
