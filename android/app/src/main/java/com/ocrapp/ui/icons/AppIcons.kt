/*
 * Copyright 2024 The Android Open Source Project
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

package com.ocrapp.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

// The twelve icons the app uses from material-icons-extended, copied verbatim from its
// 1.7.6 sources under the same names and receivers, so call sites read `Icons.Default.X`
// and only import from here. The library is ~11,000 classes that every debug build
// dexed and installed for these twelve; R8 only strips them from release builds.

public val Icons.Filled.CameraAlt: ImageVector
    get() {
        if (_cameraAlt != null) {
            return _cameraAlt!!
        }
        _cameraAlt = materialIcon(name = "Filled.CameraAlt") {
            materialPath {
                moveTo(12.0f, 12.0f)
                moveToRelative(-3.2f, 0.0f)
                arcToRelative(3.2f, 3.2f, 0.0f, true, true, 6.4f, 0.0f)
                arcToRelative(3.2f, 3.2f, 0.0f, true, true, -6.4f, 0.0f)
            }
            materialPath {
                moveTo(9.0f, 2.0f)
                lineTo(7.17f, 4.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-3.17f)
                lineTo(15.0f, 2.0f)
                lineTo(9.0f, 2.0f)
                close()
                moveTo(12.0f, 17.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                close()
            }
        }
        return _cameraAlt!!
    }

private var _cameraAlt: ImageVector? = null

public val Icons.Filled.ContentCopy: ImageVector
    get() {
        if (_contentCopy != null) {
            return _contentCopy!!
        }
        _contentCopy = materialIcon(name = "Filled.ContentCopy") {
            materialPath {
                moveTo(16.0f, 1.0f)
                lineTo(4.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                lineTo(4.0f, 3.0f)
                horizontalLineToRelative(12.0f)
                lineTo(16.0f, 1.0f)
                close()
                moveTo(19.0f, 5.0f)
                lineTo(8.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 21.0f)
                lineTo(8.0f, 21.0f)
                lineTo(8.0f, 7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
        return _contentCopy!!
    }

private var _contentCopy: ImageVector? = null

public val Icons.Filled.DeleteOutline: ImageVector
    get() {
        if (_deleteOutline != null) {
            return _deleteOutline!!
        }
        _deleteOutline = materialIcon(name = "Filled.DeleteOutline") {
            materialPath {
                moveTo(6.0f, 19.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(18.0f, 7.0f)
                lineTo(6.0f, 7.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(8.0f, 9.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(10.0f)
                lineTo(8.0f, 19.0f)
                lineTo(8.0f, 9.0f)
                close()
                moveTo(15.5f, 4.0f)
                lineToRelative(-1.0f, -1.0f)
                horizontalLineToRelative(-5.0f)
                lineToRelative(-1.0f, 1.0f)
                lineTo(5.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                lineTo(19.0f, 4.0f)
                close()
            }
        }
        return _deleteOutline!!
    }

private var _deleteOutline: ImageVector? = null

public val Icons.Filled.Description: ImageVector
    get() {
        if (_description != null) {
            return _description!!
        }
        _description = materialIcon(name = "Filled.Description") {
            materialPath {
                moveTo(14.0f, 2.0f)
                lineTo(6.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(4.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                lineTo(18.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 8.0f)
                lineToRelative(-6.0f, -6.0f)
                close()
                moveTo(16.0f, 18.0f)
                lineTo(8.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(16.0f, 14.0f)
                lineTo(8.0f, 14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(13.0f, 9.0f)
                lineTo(13.0f, 3.5f)
                lineTo(18.5f, 9.0f)
                lineTo(13.0f, 9.0f)
                close()
            }
        }
        return _description!!
    }

private var _description: ImageVector? = null

public val Icons.Filled.ExpandLess: ImageVector
    get() {
        if (_expandLess != null) {
            return _expandLess!!
        }
        _expandLess = materialIcon(name = "Filled.ExpandLess") {
            materialPath {
                moveTo(12.0f, 8.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(12.0f, 10.83f)
                lineToRelative(4.59f, 4.58f)
                lineTo(18.0f, 14.0f)
                close()
            }
        }
        return _expandLess!!
    }

private var _expandLess: ImageVector? = null

public val Icons.Filled.ExpandMore: ImageVector
    get() {
        if (_expandMore != null) {
            return _expandMore!!
        }
        _expandMore = materialIcon(name = "Filled.ExpandMore") {
            materialPath {
                moveTo(16.59f, 8.59f)
                lineTo(12.0f, 13.17f)
                lineTo(7.41f, 8.59f)
                lineTo(6.0f, 10.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(6.0f, -6.0f)
                close()
            }
        }
        return _expandMore!!
    }

private var _expandMore: ImageVector? = null

public val Icons.Filled.PhotoLibrary: ImageVector
    get() {
        if (_photoLibrary != null) {
            return _photoLibrary!!
        }
        _photoLibrary = materialIcon(name = "Filled.PhotoLibrary") {
            materialPath {
                moveTo(22.0f, 16.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                close()
                moveTo(11.0f, 12.0f)
                lineToRelative(2.03f, 2.71f)
                lineTo(16.0f, 11.0f)
                lineToRelative(4.0f, 5.0f)
                lineTo(8.0f, 16.0f)
                lineToRelative(3.0f, -4.0f)
                close()
                moveTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                close()
            }
        }
        return _photoLibrary!!
    }

private var _photoLibrary: ImageVector? = null

public val Icons.Filled.PictureAsPdf: ImageVector
    get() {
        if (_pictureAsPdf != null) {
            return _pictureAsPdf!!
        }
        _pictureAsPdf = materialIcon(name = "Filled.PictureAsPdf") {
            materialPath {
                moveTo(20.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(11.5f, 9.5f)
                curveToRelative(0.0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                lineTo(9.0f, 11.0f)
                verticalLineToRelative(2.0f)
                lineTo(7.5f, 13.0f)
                lineTo(7.5f, 7.0f)
                lineTo(10.0f, 7.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(16.5f, 11.5f)
                curveToRelative(0.0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                horizontalLineToRelative(-2.5f)
                lineTo(12.5f, 7.0f)
                lineTo(15.0f, 7.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                verticalLineToRelative(3.0f)
                close()
                moveTo(20.5f, 8.5f)
                lineTo(19.0f, 8.5f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.5f)
                lineTo(20.5f, 11.0f)
                lineTo(19.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-1.5f)
                lineTo(17.5f, 7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(1.5f)
                close()
                moveTo(9.0f, 9.5f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(-1.0f)
                lineTo(9.0f, 8.5f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                close()
                moveTo(14.0f, 11.5f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(3.0f)
                close()
            }
        }
        return _pictureAsPdf!!
    }

private var _pictureAsPdf: ImageVector? = null

public val Icons.Filled.QrCode: ImageVector
    get() {
        if (_qrCode != null) {
            return _qrCode!!
        }
        _qrCode = materialIcon(name = "Filled.QrCode") {
            materialPath {
                moveTo(3.0f, 11.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(11.0f)
                close()
                moveTo(5.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                close()
            }
            materialPath {
                moveTo(3.0f, 21.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(21.0f)
                close()
                moveTo(5.0f, 15.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(15.0f)
                close()
            }
            materialPath {
                moveTo(13.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(13.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(9.0f)
                close()
            }
            materialPath {
                moveTo(19.0f, 19.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(13.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 19.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(17.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(17.0f, 13.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
            materialPath {
                moveTo(19.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
        }
        return _qrCode!!
    }

private var _qrCode: ImageVector? = null

public val Icons.Filled.QrCodeScanner: ImageVector
    get() {
        if (_qrCodeScanner != null) {
            return _qrCodeScanner!!
        }
        _qrCodeScanner = materialIcon(name = "Filled.QrCodeScanner") {
            materialPath {
                moveTo(9.5f, 6.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(9.5f)
                moveTo(11.0f, 5.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                lineTo(11.0f, 5.0f)
                close()
                moveTo(9.5f, 14.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(9.5f)
                moveTo(11.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(13.0f)
                lineTo(11.0f, 13.0f)
                close()
                moveTo(17.5f, 6.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(17.5f)
                moveTo(19.0f, 5.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                lineTo(19.0f, 5.0f)
                close()
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(13.0f)
                verticalLineTo(13.0f)
                close()
                moveTo(14.5f, 14.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(16.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(14.5f)
                close()
                moveTo(16.0f, 13.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(13.0f)
                close()
                moveTo(13.0f, 16.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(13.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(14.5f, 17.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(17.5f)
                close()
                moveTo(16.0f, 16.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(17.5f, 14.5f)
                horizontalLineTo(19.0f)
                verticalLineTo(16.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(14.5f)
                close()
                moveTo(17.5f, 17.5f)
                horizontalLineTo(19.0f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(17.5f)
                close()
                moveTo(22.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(7.0f)
                close()
                moveTo(22.0f, 22.0f)
                verticalLineToRelative(-5.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(22.0f)
                close()
                moveTo(2.0f, 22.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(2.0f)
                verticalLineTo(22.0f)
                close()
                moveTo(2.0f, 2.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(2.0f)
                close()
            }
        }
        return _qrCodeScanner!!
    }

private var _qrCodeScanner: ImageVector? = null

public val Icons.Filled.Save: ImageVector
    get() {
        if (_save != null) {
            return _save!!
        }
        _save = materialIcon(name = "Filled.Save") {
            materialPath {
                moveTo(17.0f, 3.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 7.0f)
                lineToRelative(-4.0f, -4.0f)
                close()
                moveTo(12.0f, 19.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(15.0f, 9.0f)
                lineTo(5.0f, 9.0f)
                lineTo(5.0f, 5.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
        return _save!!
    }

private var _save: ImageVector? = null

public val Icons.AutoMirrored.Filled.OpenInNew: ImageVector
    get() {
        if (_openInNew != null) {
            return _openInNew!!
        }
        _openInNew = materialIcon(name = "AutoMirrored.Filled.OpenInNew", autoMirror = true) {
            materialPath {
                moveTo(19.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.0f)
                close()
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19.0f, 6.41f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }
        return _openInNew!!
    }

private var _openInNew: ImageVector? = null
