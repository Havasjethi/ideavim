/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2019 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

@file:Suppress("RemoveCurlyBracesFromTemplate")

package org.jetbrains.plugins.ideavim.action.motion.select.motion

import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.option.Options
import org.jetbrains.plugins.ideavim.VimTestCase

class SelectMotionRightActionTest : VimTestCase() {
    fun `test char select simple move`() {
        doTest(parseKeys("viw", "<C-G>", "<Right>"),
                """
                A Discovery

                I ${c}found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
                    """.trimIndent(),
                """
                A Discovery

                I found${c} it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
                    """.trimIndent(),
                CommandState.Mode.COMMAND,
                CommandState.SubMode.NONE)
    }

    fun `test select multiple carets`() {
        doTest(parseKeys("viw", "<C-G>", "<Right>"),
                """
                A Discovery

                I ${c}found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden san${c}d
                hard by the torrent of a mountain pass.""".trimIndent(),
                """
                A Discovery

                I found${c} it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden san${c}d
                hard by the torrent of a mountain pass.""".trimIndent(),
                CommandState.Mode.COMMAND,
                CommandState.SubMode.NONE)
    }

    fun `test without stopsel`() {
        Options.getInstance().getListOption(Options.KEYMODEL)!!.remove("stopselect")
        doTest(parseKeys("viw", "<C-G>", "<Right>"),
                """
                A Discovery

                I ${c}found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
                    """.trimIndent(),
                """
                A Discovery

                I ${s}found ${c}${se}it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
                    """.trimIndent(),
                CommandState.Mode.SELECT,
                CommandState.SubMode.VISUAL_CHARACTER)
    }
}