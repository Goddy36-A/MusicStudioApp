package com.musicstudio.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {

    fun hasMicrophonePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun hasStoragePermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }

    fun hasAllPermissions(ctx: Context): Boolean =
        hasMicrophonePermission(ctx) && hasStoragePermission(ctx)

    /** Returns the list of permissions that still need to be requested. */
    fun missingPermissions(ctx: Context): Array<String> = buildList {
        if (!hasMicrophonePermission(ctx)) add(Manifest.permission.RECORD_AUDIO)
        if (!hasStoragePermission(ctx)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            else
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}
