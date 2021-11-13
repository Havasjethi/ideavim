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

package com.maddyhome.idea.vim.listener

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManagerListener
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.find.FindModelListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Key
import com.intellij.util.PlatformUtils
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.action.motion.select.SelectToggleVisualMode
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.group.visual.IdeaSelectionControl
import com.maddyhome.idea.vim.group.visual.VimVisualTimer
import com.maddyhome.idea.vim.group.visual.moveCaretOneCharLeftFromSelectionEnd
import com.maddyhome.idea.vim.helper.EditorDataContext
import com.maddyhome.idea.vim.helper.MessageHelper
import com.maddyhome.idea.vim.helper.commandState
import com.maddyhome.idea.vim.helper.fileSize
import com.maddyhome.idea.vim.helper.getTopLevelEditor
import com.maddyhome.idea.vim.helper.inNormalMode
import com.maddyhome.idea.vim.helper.inVisualMode
import com.maddyhome.idea.vim.helper.isIdeaVimDisabledHere
import com.maddyhome.idea.vim.option.IdeaRefactorMode
import com.maddyhome.idea.vim.vimscript.model.options.helpers.IdeaRefactorModeHelper
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.NotNull

/**
 * @author Alex Plate
 */
object IdeaSpecifics {
  class VimActionListener : AnActionListener {
    @NonNls
    private val surrounderItems = listOf("if", "if / else", "for")
    private val surrounderAction =
      "com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler\$InvokeSurrounderAction"
    private var editor: Editor? = null
    override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
      if (!VimPlugin.isEnabled()) return

      val hostEditor = dataContext.getData(CommonDataKeys.HOST_EDITOR)
      if (hostEditor != null) {
        editor = hostEditor
      }

      //region Track action id
      if (FindActionId.enabled) {
        val copyActionText = MessageHelper.message("action.copy.action.id.text")
        if (copyActionText != action.templateText) {
          val id: String? = ActionManager.getInstance().getId(action)
          VimPlugin.getNotifications(dataContext.getData(CommonDataKeys.PROJECT)).notifyActionId(id)
        }
      }
      //endregion
    }

    override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
      if (!VimPlugin.isEnabled()) return

      //region Extend Selection for Rider
      if (PlatformUtils.isRider()) {
        when (ActionManager.getInstance().getId(action)) {
          IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET, IdeActions.ACTION_EDITOR_UNSELECT_WORD_AT_CARET -> {
            // Rider moves caret to the end of selection
            editor?.caretModel?.addCaretListener(object : CaretListener {
              override fun caretPositionChanged(event: CaretEvent) {
                val eventEditor = event.editor.getTopLevelEditor()
                val predictedMode =
                  IdeaSelectionControl.predictMode(eventEditor, VimListenerManager.SelectionSource.OTHER)
                moveCaretOneCharLeftFromSelectionEnd(eventEditor, predictedMode)
                eventEditor.caretModel.removeCaretListener(this)
              }
            })
          }
        }
      }
      //endregion

      //region Enter insert mode after surround with if
      if (surrounderAction == action.javaClass.name && surrounderItems.any {
        action.templatePresentation.text.endsWith(
            it
          )
      }
      ) {
        editor?.let {
          val commandState = it.commandState
          while (commandState.mode != CommandState.Mode.COMMAND) {
            commandState.popModes()
          }
          VimPlugin.getChange().insertBeforeCursor(it, dataContext)
          KeyHandler.getInstance().reset(it)
        }
      }
      //endregion

      editor = null
    }
  }

  //region Enter insert mode for surround templates without selection
  class VimTemplateManagerListener : TemplateManagerListener {
    override fun templateStarted(state: TemplateState) {
      if (!VimPlugin.isEnabled()) return
      val editor = state.editor ?: return

      state.addTemplateStateListener(object : TemplateEditingAdapter() {
        override fun currentVariableChanged(
          templateState: TemplateState,
          template: Template?,
          oldIndex: Int,
          newIndex: Int,
        ) {
          if (IdeaRefactorModeHelper.keepMode()) {
            IdeaRefactorModeHelper.correctSelection(editor)
          }
        }
      })

      if (IdeaRefactorModeHelper.keepMode()) {
        IdeaRefactorModeHelper.correctSelection(editor)
      } else {
        if (!editor.selectionModel.hasSelection()) {
          // Enable insert mode if there is no selection in template
          // Template with selection is handled by [com.maddyhome.idea.vim.group.visual.VisualMotionGroup.controlNonVimSelectionChange]
          if (editor.inNormalMode) {
            VimPlugin.getChange().insertBeforeCursor(editor, EditorDataContext.init(editor))
            KeyHandler.getInstance().reset(editor)
          }
        }
      }
    }
  }
  //endregion

  //region Register shortcuts for lookup and perform partial reset
  class LookupTopicListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
      if (!VimPlugin.isEnabled()) return

      // Lookup opened
      if (oldLookup == null && newLookup is LookupImpl) {
        if (newLookup.editor.isIdeaVimDisabledHere) return

        VimPlugin.getKey().registerShortcutsForLookup(newLookup)
      }

      // Lookup closed
      if (oldLookup != null && newLookup == null) {
        val editor = oldLookup.editor
        if (editor.isIdeaVimDisabledHere) return
        // VIM-1858
        KeyHandler.getInstance().partialReset(editor)
      }
    }
  }
  //endregion

  //region Hide Vim search highlights when showing IntelliJ search results
  class VimFindModelListener : FindModelListener {
    override fun findNextModelChanged() {
      if (!VimPlugin.isEnabled()) return
      VimPlugin.getSearch().clearSearchHighlight()
    }
  }
  //endregion

  //region Ace jump
  fun aceJumpActive(): Boolean {
    // This logic should be removed after creating more correct key processing.
    return EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT)
      .javaClass.name.startsWith("org.acejump.")
  }
  //endregion

  //region AppCode templates
  /**
   * A collection of hacks to improve the interaction with fancy AppCode templates
   */
  object AppCodeTemplates {
    private val facedAppCodeTemplate = Key.create<IntRange>("FacedAppCodeTemplate")

    private const val TEMPLATE_START = "<#T##"
    private const val TEMPLATE_END = "#>"

    class ActionListener : AnActionListener {

      private var editor: Editor? = null

      override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        if (!VimPlugin.isEnabled()) return

        val hostEditor = dataContext.getData(CommonDataKeys.HOST_EDITOR)
        if (hostEditor != null) {
          editor = hostEditor
        }
      }

      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        if (!VimPlugin.isEnabled()) return

        if (PlatformUtils.isAppCode()) {
          if (ActionManager.getInstance().getId(action) == IdeActions.ACTION_CHOOSE_LOOKUP_ITEM) {
            val myEditor = editor
            if (myEditor != null) {
              VimVisualTimer.doNow()
              if (myEditor.inVisualMode) {
                SelectToggleVisualMode.toggleMode(myEditor)
                KeyHandler.getInstance().partialReset(myEditor)
              }
            }
          }
        }
      }
    }

    @JvmStatic
    fun onMovement(
      editor: @NotNull Editor,
      caret: @NotNull Caret,
      toRight: Boolean,
    ) {
      if (!PlatformUtils.isAppCode()) return

      val offset = caret.offset
      val offsetRightEnd = offset + TEMPLATE_START.length
      val offsetLeftEnd = offset - 1
      val templateRange = caret.getUserData(facedAppCodeTemplate)
      if (templateRange == null) {
        if (offsetRightEnd < editor.fileSize &&
          editor.document.charsSequence.subSequence(offset, offsetRightEnd).toString() == TEMPLATE_START
        ) {
          caret.shake()

          val templateEnd = editor.findTemplateEnd(offset) ?: return

          caret.putUserData(facedAppCodeTemplate, offset..templateEnd)
        }
        if (offsetLeftEnd >= 0 &&
          offset + 1 <= editor.fileSize &&
          editor.document.charsSequence.subSequence(offsetLeftEnd, offset + 1).toString() == TEMPLATE_END
        ) {
          caret.shake()

          val templateStart = editor.findTemplateStart(offsetLeftEnd) ?: return

          caret.putUserData(facedAppCodeTemplate, templateStart..offset)
        }
      } else {
        if (offset in templateRange) {
          if (toRight) {
            caret.moveToOffset(templateRange.last + 1)
          } else {
            caret.moveToOffset(templateRange.first)
          }
        }
        caret.putUserData(facedAppCodeTemplate, null)
        caret.shake()
      }
    }

    fun Editor.appCodeTemplateCaptured(): Boolean {
      if (!PlatformUtils.isAppCode()) return false
      return this.caretModel.allCarets.any { it.getUserData(facedAppCodeTemplate) != null }
    }

    private fun Caret.shake() {
      moveCaretRelatively(1, 0, false, false)
      moveCaretRelatively(-1, 0, false, false)
    }

    private fun Editor.findTemplateEnd(start: Int): Int? {
      val charSequence = this.document.charsSequence
      val length = charSequence.length
      for (i in start until length - 1) {
        if (charSequence[i] == TEMPLATE_END[0] && charSequence[i + 1] == TEMPLATE_END[1]) {
          return i + 1
        }
      }
      return null
    }

    private fun Editor.findTemplateStart(start: Int): Int? {
      val charSequence = this.document.charsSequence
      val templateLastIndex = TEMPLATE_START.length
      for (i in start downTo templateLastIndex) {
        if (charSequence.subSequence(i - templateLastIndex, i).toString() == TEMPLATE_START) {
          return i - templateLastIndex
        }
      }
      return null
    }
  }
  //endregion
}

//region Find action ID
class FindActionIdAction : DumbAwareToggleAction() {
  override fun isSelected(e: AnActionEvent): Boolean = FindActionId.enabled

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    FindActionId.enabled = state
  }
}

object FindActionId {
  var enabled = false
}
//endregion
