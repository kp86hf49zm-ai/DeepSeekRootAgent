package com.deepseek.rootagent

import android.content.Context
import java.io.File

/**
 * 内置 Alpine Linux 环境：
 * - 首次启动把 assets/alpine-rootfs.tar 解压到 filesDir/alpine
 * - 配置 DNS、apk 阿里云源（HTTP 80 避免 403）
 * - PATH 软链桥接 Android 继承的路径
 * - TMPDIR 软链到 /tmp
 * - 预装 python3/pip/curl/wget/git/bash/vim/htop/nodejs/npm
 * - 通过 chroot 执行，每次命令前 export 干净 PATH
 */
object LinuxEnv {
    @Volatile
    private var ready = false
    @Volatile
    private var mounted = false

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "alpine")

    fun isReady(): Boolean = ready

    fun ensure(ctx: Context): Boolean {
        if (ready) return true
        val dir = rootfsDir(ctx)
        val marker = File(dir, ".ready")
        if (marker.exists()) {
            ready = true
            return true
        }
        LogManager.log("==== LinuxEnv: extracting Alpine rootfs ====")
        try {
            dir.mkdirs()
            ctx.assets.open("alpine-rootfs.tar").use { input ->
                val tmp = File(ctx.cacheDir, "alpine-rootfs.tar")
                tmp.outputStream().use { input.copyTo(it) }
                val r = RootShell.exec(
                    "cd ${dir.absolutePath} && tar xf ${tmp.absolutePath}",
                    timeoutSec = 120
                )
                tmp.delete()
                if (r.code != 0) {
                    LogManager.log("  !! extract failed: ${r.combined()}")
                    return false
                }
            }
            // 写 apk 源（阿里云 HTTP 80，避免 HTTPS 403）
            RootShell.exec(
                """cat > ${dir.absolutePath}/etc/apk/repositories <<'EOF'
http://mirrors.aliyun.com/alpine/v3.20/main
http://mirrors.aliyun.com/alpine/v3.20/community
EOF""",
                timeoutSec = 5
            )
            // DNS
            RootShell.exec(
                """printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > ${dir.absolutePath}/etc/resolv.conf""",
                timeoutSec = 5
            )
            // 挂载
            doMount(dir.absolutePath)
            // TMPDIR 软链，删掉错误的 PATH 软链
            RootShell.exec(
                """
                mkdir -p ${dir.absolutePath}/data/user/0/com.deepseek.rootagent
                ln -sfn /tmp ${dir.absolutePath}/data/user/0/com.deepseek.rootagent/cache
                rm -rf ${dir.absolutePath}/product ${dir.absolutePath}/system ${dir.absolutePath}/system_ext
                """.trimIndent(),
                timeoutSec = 5
            )
            // 预装
            LogManager.log("==== LinuxEnv: installing packages ====")
            RootShell.exec(
                """chroot ${dir.absolutePath} /bin/sh -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && apk add --no-cache bash python3 py3-pip curl wget git vim htop nodejs npm build-base 2>&1 | tail -5'""",
                timeoutSec = 180
            )
            RootShell.exec("touch ${marker.absolutePath}", timeoutSec = 5)
            ready = true
            mounted = true
            LogManager.log("==== LinuxEnv: ready ====")
            return true
        } catch (e: Exception) {
            LogManager.log("  !! LinuxEnv error: ${e.message}")
            return false
        }
    }

    private fun doMount(dir: String) {
        RootShell.exec(
            """
            mount --bind /dev $dir/dev 2>/dev/null
            mount --bind /proc $dir/proc 2>/dev/null
            mount --bind /sys $dir/sys 2>/dev/null
            chmod 1777 $dir/tmp
            """.trimIndent(),
            timeoutSec = 5
        )
        mounted = true
    }

    /** 在 Linux 环境里执行命令 */
    fun exec(ctx: Context, cmd: String, timeoutSec: Int = 60): RootShell.Result {
        val dir = rootfsDir(ctx).absolutePath
        if (!mounted) doMount(dir)
        val escaped = cmd.replace("'", "'\\''")
        // 每次命令前 export 干净 PATH，避免 Android PATH 污染
        val wrapped = """chroot $dir /bin/sh -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && cd /root && $escaped'"""
        return RootShell.exec(wrapped, timeoutSec)
    }
}
