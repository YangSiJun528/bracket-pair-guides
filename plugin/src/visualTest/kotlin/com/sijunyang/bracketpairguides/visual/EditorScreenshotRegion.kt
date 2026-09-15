package com.sijunyang.bracketpairguides.visual

import java.awt.image.BufferedImage

/** Fixed content region; the editor component's first row can contain the selected tab edge. */
internal object EditorScreenshotRegion {
    const val X = 0
    const val Y = 1
    const val WIDTH = 220
    const val HEIGHT = 239

    fun crop(screenshot: BufferedImage): BufferedImage {
        require(screenshot.width >= X + WIDTH && screenshot.height >= Y + HEIGHT) {
            "Code editor is too small for the pinned crop ($X, $Y, $WIDTH, $HEIGHT): " +
                "${screenshot.width}x${screenshot.height}"
        }
        return BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, WIDTH, HEIGHT, screenshot.getRGB(X, Y, WIDTH, HEIGHT, null, 0, WIDTH), 0, WIDTH)
        }
    }
}
