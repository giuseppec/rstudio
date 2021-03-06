/*
 * EditingTargetCodeExecution.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.source.editors;

import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.rmarkdown.events.SendToChunkConsoleEvent;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleExecutePendingInputEvent;
import org.rstudio.studio.client.workbench.views.console.events.SendToConsoleEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay.AnchoredSelection;
import org.rstudio.studio.client.workbench.views.source.editors.text.Scope;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Range;

import com.google.inject.Inject;

public class EditingTargetCodeExecution
{
   public interface CodeExtractor
   {
      String extractCode(DocDisplay docDisplay, Range range);
   }
   
   public EditingTargetCodeExecution(DocDisplay docDisplay, String docId)
   {
      this(docDisplay, docId, new CodeExtractor() {
         @Override
         public String extractCode(DocDisplay docDisplay, Range range)
         {
            return docDisplay.getCode(range.getStart(), range.getEnd());
         }
      });
   }
   
   public EditingTargetCodeExecution(DocDisplay docDisplay,
                                     String docId,
                                     CodeExtractor codeExtractor)
   {
      docDisplay_ = docDisplay;
      codeExtractor_ = codeExtractor;
      docId_ = docId;
      RStudioGinjector.INSTANCE.injectMembers(this);
   }
   
   @Inject
   void initialize(EventBus events, UIPrefs prefs, Commands commands)
   {
      events_ = events;
      prefs_ = prefs;
      commands_ = commands;
   }
   
   public void executeSelection(boolean consoleExecuteWhenNotFocused,
                                boolean moveCursorAfter)
   {
      executeSelectionMaybeNoFocus(consoleExecuteWhenNotFocused,
            moveCursorAfter,
            null);
   }
   
   public void executeSelection(boolean consoleExecuteWhenNotFocused,
         boolean moveCursorAfter,
         String functionWrapper)
   {  
      Range selectionRange = docDisplay_.getSelectionRange();
      boolean noSelection = selectionRange.isEmpty();
      if (noSelection)
      {
         int row = docDisplay_.getSelectionStart().getRow();
         selectionRange = Range.fromPoints(
               Position.create(row, 0),
               Position.create(row, docDisplay_.getLength(row)));
      }

      executeRange(selectionRange, functionWrapper);
      
      // advance if there is no current selection
      if (noSelection && moveCursorAfter)
      {
         if (!docDisplay_.moveSelectionToNextLine(true))
            docDisplay_.moveSelectionToBlankLine();
         docDisplay_.scrollCursorIntoViewIfNecessary(3);
      }
   }
   
   public void executeSelection(boolean consoleExecuteWhenNotFocused)
   {
      executeSelectionMaybeNoFocus(consoleExecuteWhenNotFocused,
            true,
            null);
   }
   
   public void executeRange(Range range)
   {
      executeRange(range, null);
   }
   
   public void profileSelection()
   {
      // allow console a chance to execute code if we aren't focused
      if (!docDisplay_.isFocused())
      {
         events_.fireEvent(new ConsoleExecutePendingInputEvent(
               commands_.profileCodeWithoutFocus().getId()));
         return;
      }
      
      executeSelection(false, false, "profvis::profvis");
   }
   
   private void executeSelectionMaybeNoFocus(boolean consoleExecuteWhenNotFocused,
                                             boolean moveCursorAfter,
                                             String functionWrapper)
   {
      // allow console a chance to execute code if we aren't focused
      if (consoleExecuteWhenNotFocused && !docDisplay_.isFocused())
      {
         events_.fireEvent(new ConsoleExecutePendingInputEvent(
               commands_.executeCodeWithoutFocus().getId()));
         return;
      }
      
      executeSelection(consoleExecuteWhenNotFocused, moveCursorAfter, functionWrapper);
   }
   
   private void executeRange(Range range, String functionWrapper)
   {
      String code = codeExtractor_.extractCode(docDisplay_, range);
     
      setLastExecuted(range.getStart(), range.getEnd());
      
      // trim intelligently
      code = code.trim();
      if (code.length() == 0)
         code = "\n";
      
      // strip roxygen off the beginning of lines
      if (isRoxygenExampleRange(range))
      {
         code = code.replaceFirst("^[ \\t]*#'[ \\t]?", "");
         code = code.replaceAll("\n[ \\t]*#'[ \\t]?", "\n");
      }
      
      // if we're in a chunk with in-line output, execute it there instead
      if (docDisplay_.showChunkOutputInline())
      {
         Scope scope = docDisplay_.getCurrentChunk(range.getStart());
         if (scope != null)
         {
            events_.fireEvent(new SendToChunkConsoleEvent(docId_, 
                  scope, code));
            return;
         }
      }
      
      if (functionWrapper != null)
      {
         code = functionWrapper + "({" + code + "})";
      }
      
      // send to console
      events_.fireEvent(new SendToConsoleEvent(
                                  code, 
                                  true, 
                                  prefs_.focusConsoleAfterExec().getValue()));
   }
   
   public void executeLastCode()
   {
      if (lastExecutedCode_ != null)
      {
         String code = lastExecutedCode_.getValue();
         if (code != null && code.trim().length() > 0)
         {
            events_.fireEvent(new SendToConsoleEvent(code, true));
         }
      }
   }
   
   public void setLastExecuted(Position start, Position end)
   {
      detachLastExecuted();
      lastExecutedCode_ = docDisplay_.createAnchoredSelection(start, end);
   }

   public void detachLastExecuted()
   {
      if (lastExecutedCode_ != null)
      {
         lastExecutedCode_.detach();
         lastExecutedCode_ = null;
      }
   }
   
   private boolean isRoxygenExampleRange(Range range)
   {
      // ensure all of the lines in the selection are within roxygen
      int selStartRow = range.getStart().getRow();
      int selEndRow = range.getEnd().getRow();
      
      // ignore the last row if it's column 0
      if (range.getEnd().getColumn() == 0)
         selEndRow = Math.max(selEndRow-1, selStartRow);
      
      for (int i=selStartRow; i<=selEndRow; i++)
      {
         if (!isRoxygenLine(docDisplay_.getLine(i)))
            return false;
      }
      
      // scan backwards and look for @example
      int row = selStartRow;
      while (--row >= 0)
      {
         String line = docDisplay_.getLine(row);
         
         // must still be within roxygen
         if (!isRoxygenLine(line))
            return false;
         
         // if we are in an example block return true
         if (line.matches("^\\s*#'\\s*@example.*$"))
            return true;
      }
      
      // didn't find the example block
      return false;
   }
   
   private boolean isRoxygenLine(String line)
   {
      String trimmedLine = line.trim();
      return (trimmedLine.length() == 0) || trimmedLine.startsWith("#'");
   }
   
   private EventBus events_;
   private UIPrefs prefs_;
   private final DocDisplay docDisplay_;
   private final CodeExtractor codeExtractor_;
   private final String docId_;
   private AnchoredSelection lastExecutedCode_;
   private Commands commands_;
}

