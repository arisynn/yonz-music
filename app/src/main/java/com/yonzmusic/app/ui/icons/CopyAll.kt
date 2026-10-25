/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yonzmusic.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.CopyAll: ImageVector
    get() {
        if (_copyAll != null) {
            return _copyAll!!
        }
        _copyAll = materialIcon(name = "Rounded.CopyAll") {
            materialPath {
                moveTo(18.0f, 2.0f)
                horizontalLineTo(9.0f)
                curveTo(7.9f, 2.0f, 7.0f, 2.9f, 7.0f, 4.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(9.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(4.0f)
                curveTo(20.0f, 2.9f, 19.1f, 2.0f, 18.0f, 2.0f)
                close()
                moveTo(18.0f, 16.0f)
                horizontalLineTo(9.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(9.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(3.0f, 15.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(3.0f, 9.5f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(9.5f)
                close()
                moveTo(10.0f, 20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(3.0f, 18.5f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(5.0f, 22.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(22.0f)
                close()
                moveTo(8.5f, 22.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(22.0f)
                close()
                moveTo(13.5f, 22.0f)
                lineTo(13.5f, 22.0f)
                lineToRelative(0.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(0.0f)
                curveTo(15.5f, 21.1f, 14.6f, 22.0f, 13.5f, 22.0f)
                close()
                moveTo(5.0f, 6.0f)
                lineTo(5.0f, 6.0f)
                lineToRelative(0.0f, 2.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(0.0f)
                curveTo(3.0f, 6.9f, 3.9f, 6.0f, 5.0f, 6.0f)
                close()
            }
        }
        return _copyAll!!
    }

private var _copyAll: ImageVector? = null
