/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2021 The IdeaVim authors
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.ex.ranges.Ranges
import com.maddyhome.idea.vim.group.MarkGroup.DEL_FILE_MARKS
import com.maddyhome.idea.vim.group.MarkGroup.DEL_MARKS
import com.maddyhome.idea.vim.group.MarkGroup.RO_GLOBAL_MARKS
import com.maddyhome.idea.vim.group.MarkGroup.WR_GLOBAL_MARKS
import com.maddyhome.idea.vim.group.MarkGroup.WR_REGULAR_FILE_MARKS
import com.maddyhome.idea.vim.helper.MessageHelper
import com.maddyhome.idea.vim.helper.Msg
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.VimContext

private val VIML_COMMENT = Regex("(?<!\\\\)\".*")
private val TRAILING_SPACES = Regex("\\s*$")
private val ARGUMENT_DELETE_ALL_FILE_MARKS = Regex("^!$")

private const val ESCAPED_QUOTE = "\\\""
private const val UNESCAPED_QUOTE = "\""

/**
 * @author Jørgen Granseth
 * see "h :delmarks"
 */
data class DeleteMarksCommand(val ranges: Ranges, val argument: String) : Command.SingleExecution(ranges, argument) {
  override val argFlags = flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(editor: Editor, context: DataContext, vimContext: VimContext): ExecutionResult {
    val processedArg = argument
      .replace(VIML_COMMENT, "")
      .replace(ESCAPED_QUOTE, UNESCAPED_QUOTE)
      .replace(TRAILING_SPACES, "")
      .replace(ARGUMENT_DELETE_ALL_FILE_MARKS, DEL_FILE_MARKS)
      .replaceRanges(WR_REGULAR_FILE_MARKS)
      .replaceRanges(WR_GLOBAL_MARKS)
      .replaceRanges(RO_GLOBAL_MARKS)

    processedArg.indexOfFirst { it !in " $DEL_MARKS" }.let { index ->
      if (index != -1) {
        val invalidIndex = if (processedArg[index] == '-') (index - 1).coerceAtLeast(0) else index

        VimPlugin.showMessage(MessageHelper.message(Msg.E475, processedArg.substring(invalidIndex)))
        return ExecutionResult.Error
      }
    }

    processedArg.forEach { character -> deleteMark(editor, character) }

    return ExecutionResult.Success
  }
}

private fun deleteMark(editor: Editor, character: Char) {
  if (character != ' ') {
    val markGroup = VimPlugin.getMark()
    val mark = markGroup.getMark(editor, character) ?: return
    markGroup.removeMark(character, mark)
  }
}

private fun String.replaceRanges(range: String): String {
  return Regex("[$range]-[$range]").replace(this) { match ->
    val startChar = match.value[0]
    val endChar = match.value[2]

    val startIndex = range.indexOf(startChar)
    val endIndex = range.indexOf(endChar)

    if (startIndex >= 0 && endIndex >= 0 && startIndex <= endIndex) {
      range.subSequence(startIndex, endIndex + 1)
    } else {
      match.value
    }
  }
}
