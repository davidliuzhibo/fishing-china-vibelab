package com.vrgames.fishing

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.vrgames.fishing.content.LakeStage
import com.vrgames.fishing.content.LogWindow
import com.vrgames.fishing.content.MapWindow

const val LOG_ID = "Log"
const val MAP_ID = "Map"

/**
 * Three linked spaces: the fishing place all around (a full-immersion Stage), the 鱼类图鉴 (every
 * fish and its record) and the 钓场地图 (lakes, stars, trophies). They share [com.vrgames.fishing.game.Game].
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            PicoTheme { LakeStage() }
        }
        WindowContainer(LOG_ID, Form.Planar, { defaultSize = WindowContainerSize(820.dp, 1000.dp) }) {
            PicoTheme { LogWindow() }
        }
        WindowContainer(MAP_ID, Form.Planar, { defaultSize = WindowContainerSize(820.dp, 1000.dp) }) {
            PicoTheme { MapWindow() }
        }
    }
