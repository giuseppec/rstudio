/*
 * ChunkOutputWidget.java
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
package org.rstudio.studio.client.workbench.views.source.editors.text;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.VirtualConsole;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.dom.ImageElementEx;
import org.rstudio.core.client.js.JsArrayEx;
import org.rstudio.core.client.widget.PreWidget;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.application.events.InterruptStatusEvent;
import org.rstudio.studio.client.application.events.RestartStatusEvent;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOutput;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOutputUnit;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputHandler;
import org.rstudio.studio.client.workbench.views.source.editors.text.rmd.TextEditingTargetNotebook;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.dom.client.Style.Overflow;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

public class ChunkOutputWidget extends Composite
                               implements ConsoleWriteOutputHandler,
                                          ConsoleWriteErrorHandler,
                                          ConsolePromptHandler,
                                          RestartStatusEvent.Handler,
                                          InterruptStatusEvent.Handler
{

   private static ChunkOutputWidgetUiBinder uiBinder = GWT
         .create(ChunkOutputWidgetUiBinder.class);

   interface ChunkOutputWidgetUiBinder
         extends UiBinder<Widget, ChunkOutputWidget>
   {
      
   }
   
   public interface Resources extends ClientBundle
   {
      @Source("ExpandChunkIcon.png")
      ImageResource expandChunkIcon();
      
      @Source("CollapseChunkIcon.png")
      ImageResource collapseChunkIcon();

      @Source("RemoveChunkIcon.png")
      ImageResource removeChunkIcon();
   }
   
   public interface ChunkStyle extends CssResource
   {
      String overflowY();
      String collapsed();
   }

   public ChunkOutputWidget(String chunkId,
         CommandWithArg<Integer> onRenderCompleted,
         final Command onChunkCleared)
   {
      chunkId_ = chunkId;
      initWidget(uiBinder.createAndBindUi(this));
      applyCachedEditorStyle();
      
      onRenderCompleted_ = onRenderCompleted;
      
      DOM.sinkEvents(clear_.getElement(), Event.ONCLICK);
      DOM.setEventListener(clear_.getElement(), new EventListener()
      {
         @Override
         public void onBrowserEvent(Event evt)
         {
            switch(DOM.eventGetType(evt))
            {
            case Event.ONCLICK:
               destroyConsole();
               onChunkCleared.execute();
               break;
            };
         }
      });
      
      EventListener toggleExpansion = new EventListener()
      {
         @Override
         public void onBrowserEvent(Event evt)
         {
            switch(DOM.eventGetType(evt))
            {
            case Event.ONCLICK:
               toggleExpansionState();
               break;
            };
         }
      };

      DOM.sinkEvents(expander_.getElement(), Event.ONCLICK);
      DOM.setEventListener(expander_.getElement(), toggleExpansion);
      
      DOM.sinkEvents(expand_.getElement(), Event.ONCLICK);
      DOM.setEventListener(expand_.getElement(), toggleExpansion);
      
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.addHandler(RestartStatusEvent.TYPE, this);
      events.addHandler(InterruptStatusEvent.TYPE, this);
   }
   
   public void showChunkOutputUnit(RmdChunkOutputUnit unit)
   {
      initializeOutput(unit.getType());
      switch(unit.getType())
      {
      case RmdChunkOutputUnit.TYPE_TEXT:
         showConsoleOutput(unit.getArray());
         break;
      case RmdChunkOutputUnit.TYPE_HTML:
         showHtmlOutput(unit.getString());
         break;
      case RmdChunkOutputUnit.TYPE_PLOT:
         showPlotOutput(unit.getString());
         break;
      }
   }

   public void showChunkOutput(RmdChunkOutput output)
   {
      if (output.getType() == RmdChunkOutput.TYPE_MULTIPLE_UNIT)
      {
         // loop over the output units and emit the appropriate contents for
         // each
         JsArray<RmdChunkOutputUnit> units = output.getUnits();
         for (int i = 0; i < units.length(); i++)
         {
            showChunkOutputUnit(units.get(i));
         }
         onOutputFinished();
      }
      else if (output.getType() == RmdChunkOutput.TYPE_SINGLE_UNIT)
      {
         showChunkOutputUnit(output.getUnit());
      }
   }
   
   public void syncHeight(boolean scrollToBottom)
   {
      // don't sync if we're collapsed
      if (expansionState_ != EXPANDED)
         return;
      
      int height = root_.getElement().getScrollHeight();
      if (height == renderedHeight_)
         return;
      renderedHeight_ = height;
      if (scrollToBottom)
         root_.getElement().setScrollTop(height);
      frame_.getElement().getStyle().setHeight(height, Unit.PX);
      onRenderCompleted_.execute(height);
   }

   private String classOfOutput(int type)
   {
      if (type == CONSOLE_ERROR)
        return RStudioGinjector.INSTANCE.getUIPrefs().getThemeErrorClass();
      else if (type == CONSOLE_INPUT)
        return "ace_keyword";
      return null;
   }
   
   private void showConsoleOutput(JsArray<JsArrayEx> output)
   {
      initConsole();
      for (int i = 0; i < output.length(); i++)
      {
         if (output.get(i).length() < 2)
            continue;
         vconsole_.submit(
               (output.get(i).getInt(0) == CONSOLE_INPUT ? 
                     "> " + output.get(i).getString(1)  + "\n" :
                     output.get(i).getString(1)), 
               classOfOutput(output.get(i).getInt(0)));
      }
      vconsole_.redraw(console_.getElement());
      setOverflowStyle();
   }
   
   private void completeUnitRender()
   {
      syncHeight(true);
   }
   
   private void showPlotOutput(String url)
   {
      final Image plot = new Image();

      // set to auto height and hidden -- we need the image to load so we can 
      // compute its natural width before showing it
      plot.getElement().getStyle().setProperty("height", "auto");
      plot.getElement().getStyle().setDisplay(Display.NONE);

      root_.add(plot);
      
      DOM.sinkEvents(plot.getElement(), Event.ONLOAD);
      DOM.setEventListener(plot.getElement(), new EventListener()
      {
         @Override
         public void onBrowserEvent(Event event)
         {
            if (DOM.eventGetType(event) != Event.ONLOAD)
               return;
            
            ImageElementEx img = plot.getElement().cast();

            // grow the image to fill the container, but not beyond its
            // natural width
            img.getStyle().setWidth(100, Unit.PCT);
            img.getStyle().setProperty("maxWidth", img.naturalWidth() + "px");

            // show the image
            plot.getElement().getStyle().setDisplay(Display.BLOCK);

            completeUnitRender();
         }
      });

      plot.setUrl(url);
   }
   
   private void showHtmlOutput(String url)
   {
      final ChunkOutputFrame frame = new ChunkOutputFrame();
      frame.getElement().getStyle().setHeight(500, Unit.PX);
      frame.getElement().getStyle().setWidth(100, Unit.PCT);
      root_.add(frame);

      frame.loadUrl(url, new Command() 
      {
         @Override
         public void execute()
         {
            Style bodyStyle = frame.getDocument().getBody().getStyle();
            bodyStyle.setPadding(0, Unit.PX);
            bodyStyle.setMargin(0, Unit.PX);
            bodyStyle.setColor(s_color);
            completeUnitRender();
         };
      });
   }
   
   @Override
   public void onConsoleWriteOutput(ConsoleWriteOutputEvent event)
   {
      if (event.getConsole() != chunkId_)
         return;
      renderConsoleOutput(event.getOutput(), classOfOutput(CONSOLE_OUTPUT));
   }
   
   @Override
   public void onConsoleWriteError(ConsoleWriteErrorEvent event)
   {
      if (event.getConsole() != chunkId_)
         return;
      renderConsoleOutput(event.getError(), classOfOutput(CONSOLE_ERROR));
   }
   
   @Override
   public void onConsolePrompt(ConsolePromptEvent event)
   {
      // stop listening to the console once the prompt appears, but don't 
      // clear up output until we receive the output finished event
      unregisterConsoleEvents();
   }
   
   @Override
   public void onRestartStatus(RestartStatusEvent event)
   {
      if (event.getStatus() != RestartStatusEvent.RESTART_COMPLETED)
         return;
      
      // when R is restarted, we're not going to get any more output, so act
      // as though the server told us it's done
      if (state_ != CHUNK_READY)
      {
         onOutputFinished();
      }
   }

   @Override
   public void onInterruptStatus(InterruptStatusEvent event)
   {
      if (event.getStatus() != InterruptStatusEvent.INTERRUPT_COMPLETED)
         return;
      
      completeInterrupt();
   }

   public void onOutputFinished()
   {
      if (state_ == CHUNK_PRE_OUTPUT)
      {
         // if no output was produced, clear the contents and show the empty
         // indicator
         emptyIndicator_.setVisible(true);
         if (vconsole_ != null)
            vconsole_.clear();
         root_.clear();
      }
      syncHeight(true);
      state_ = CHUNK_READY;
      lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
      setOverflowStyle();
      showReadyState();
   }

   private void renderConsoleOutput(String text, String clazz)
   {
      initializeOutput(RmdChunkOutputUnit.TYPE_TEXT);
      vconsole_.submitAndRender(text, clazz,
            console_.getElement());
      syncHeight(true);
   }
   
   private void initializeOutput(int outputType)
   {
      if (state_ == CHUNK_PRE_OUTPUT)
      {
         // if no output has been emitted yet, clean up all existing output
         if (vconsole_ != null)
            vconsole_.clear();
         root_.clear();
         emptyIndicator_.setVisible(false);
         lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
         state_ = CHUNK_POST_OUTPUT;
      }
      if (state_ == CHUNK_POST_OUTPUT)
      {
         if (lastOutputType_ == outputType)
            return;
         if (outputType == RmdChunkOutputUnit.TYPE_TEXT)
         {
            // if switching to textual output, allocate a new virtual console
            initConsole();
         }
         else if (lastOutputType_ == RmdChunkOutputUnit.TYPE_TEXT)
         {
            // if switching from textual input, clear the text accumulator
            if (vconsole_ != null)
               vconsole_.clear();
            console_ = null;
         }
      }
      lastOutputType_ = outputType;
   }

   public void showServerError(ServerError error)
   {
      // consider: less obtrusive error message 
      RStudioGinjector.INSTANCE.getGlobalDisplay().showErrorMessage(
            "Chunk Execution Error", error.getMessage());
      
      // treat as an interrupt (don't clear output)
      completeInterrupt();
   }
   
   public void applyCachedEditorStyle()
   {
      if (!isEditorStyleCached())
         return;
      Style frameStyle = frame_.getElement().getStyle();
      frameStyle.setBorderColor(s_outlineColor);
      emptyIndicator_.getElement().getStyle().setColor(s_color);

      // apply the style to any frames in the output
      for (Widget w: root_)
      {
         if (w instanceof ChunkOutputFrame)
         {
            ChunkOutputFrame frame = (ChunkOutputFrame)w;
            Style bodyStyle = frame.getDocument().getBody().getStyle();
            bodyStyle.setColor(s_color);
         }
      }
      frame_.getElement().getStyle().setBackgroundColor(s_backgroundColor);
   }
   
   public void setCodeExecuting(boolean entireChunk)
   {
      // expand if currently collapsed
      if (expansionState_ == COLLAPSED)
         toggleExpansionState();

      // do nothing if code is already executing
      if (state_ == CHUNK_PRE_OUTPUT || 
          state_ == CHUNK_POST_OUTPUT)
      {
         return;
      }

      registerConsoleEvents();
      state_ = CHUNK_PRE_OUTPUT;
      showBusyState();
   }
   
   public static void cacheEditorStyle(Element editorContainer, 
         Style editorStyle)
   {
      s_backgroundColor = editorStyle.getBackgroundColor();
      s_color = editorStyle.getColor();
      JsArrayString classes = JsArrayString.createArray().cast();
      classes.push("ace_marker-layer");
      classes.push("ace_foreign_line");
      s_busyColor = DomUtils.extractCssValue(classes, "backgroundColor");
      s_outlineColor = s_busyColor;
   }
   
   private void initConsole()
   {
      if (vconsole_ == null)
         vconsole_ = new VirtualConsole();
      else
         vconsole_.clear();
      if (console_ == null)
      {
         console_ = new PreWidget();
         console_.getElement().removeAttribute("tabIndex");
         console_.getElement().getStyle().setMarginTop(0, Unit.PX);
         console_.getElement().getStyle().setProperty("whiteSpace", "pre-wrap");
      }
      else
      {
         console_.getElement().setInnerHTML("");
      }

      // attach the console
      root_.add(console_);
   }
   
   private void destroyConsole()
   {
      if (vconsole_ != null)
         vconsole_.clear();
      if (console_ != null)
         console_.removeFromParent();
      console_ = null;
   }
   
   private void registerConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.addHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.addHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.addHandler(ConsolePromptEvent.TYPE, this);
   }

   private void unregisterConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.removeHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.removeHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.removeHandler(ConsolePromptEvent.TYPE, this);
   }
   
   public static boolean isEditorStyleCached()
   {
      return s_backgroundColor != null &&
             s_color != null &&
             s_outlineColor != null;
   }
   
   private void showBusyState()
   {
      getElement().getStyle().setBackgroundColor(s_busyColor);
      clear_.setVisible(false);
   }

   private void showReadyState()
   {
      getElement().getStyle().setBackgroundColor(s_backgroundColor);
      clear_.setVisible(true);
   }
   
   private void setOverflowStyle()
   {
      Element ele = root_.getElement();
      boolean hasOverflow = ele.getScrollHeight() > ele.getOffsetHeight();
      if (hasOverflow && !root_.getElement().hasClassName(style.overflowY()))
      {
         frame_.getElement().addClassName(style.overflowY());
      }
      else if (!hasOverflow && 
               root_.getElement().hasClassName(style.overflowY()))
      {
         frame_.getElement().removeClassName(style.overflowY());
      }
   }
   
   private void completeInterrupt()
   {
      if (state_ == CHUNK_PRE_OUTPUT ||
          state_ == CHUNK_POST_OUTPUT)
      {
         state_ = CHUNK_READY;
      }
      else
      {
         return;
      }
      showReadyState();
   }
   
   private void toggleExpansionState()
   {
      // cancel any previously running expand/collapse timer
      if (collapseTimer_ != null && collapseTimer_.isRunning())
          collapseTimer_.cancel();

      if (expansionState_ == EXPANDED)
      {
         getElement().addClassName(style.collapsed());

         // remove scrollbars
         root_.getElement().getStyle().setOverflow(Overflow.HIDDEN);
         root_.getElement().getStyle().setOpacity(0);
         frame_.getElement().getStyle().setProperty("transition", 
               "height " + ANIMATION_DUR + "ms ease");
         frame_.getElement().getStyle().setHeight(
               TextEditingTargetNotebook.MIN_CHUNK_HEIGHT, Unit.PX);
         collapseTimer_ = new Timer()
         {
            @Override
            public void run()
            {
               renderedHeight_ = TextEditingTargetNotebook.MIN_CHUNK_HEIGHT;
               onRenderCompleted_.execute(
                     TextEditingTargetNotebook.MIN_CHUNK_HEIGHT);
            }
            
         };
         expansionState_ = COLLAPSED;
      }
      else
      {
         getElement().removeClassName(style.collapsed());

         // restore scrollbars if necessary
         root_.getElement().getStyle().clearOverflow();
         root_.getElement().getStyle().clearOpacity();
         expansionState_ = EXPANDED;
         syncHeight(true);
         collapseTimer_ = new Timer()
         {
            @Override
            public void run()
            {
               frame_.getElement().getStyle().clearProperty("transition");
            }
         };
      }
      collapseTimer_.schedule(ANIMATION_DUR);
   }
   
   @UiField Image clear_;
   @UiField Image expand_;
   @UiField Label emptyIndicator_;
   @UiField HTMLPanel root_;
   @UiField ChunkStyle style;
   @UiField HTMLPanel frame_;
   @UiField HTMLPanel expander_;
   
   private PreWidget console_;
   private VirtualConsole vconsole_;
   
   private int state_ = CHUNK_EMPTY;
   private int expansionState_ = EXPANDED;
   private int lastOutputType_ = RmdChunkOutputUnit.TYPE_NONE;
   private int renderedHeight_ = 0;
   
   private CommandWithArg<Integer> onRenderCompleted_;
   private Timer collapseTimer_ = null;
   private final String chunkId_;

   private static String s_outlineColor    = null;
   private static String s_backgroundColor = null;
   private static String s_color           = null;
   private static String s_busyColor       = null;
   
   private final static int EXPANDED   = 0;
   private final static int COLLAPSED  = 1;
   private final static int ANIMATION_DUR = 400;
   
   public final static int CHUNK_EMPTY       = 1;
   public final static int CHUNK_READY       = 2;
   public final static int CHUNK_PRE_OUTPUT  = 3;
   public final static int CHUNK_POST_OUTPUT = 4;
   
   public final static int CONSOLE_INPUT  = 0;
   public final static int CONSOLE_OUTPUT = 1;
   public final static int CONSOLE_ERROR  = 2;
}
