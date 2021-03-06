/*
 * NotebookHtmlWidgets.hpp
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


#ifndef SESSION_NOTEBOOK_HTML_WIGETS_HPP
#define SESSION_NOTEBOOK_HTML_WIGETS_HPP

#include <boost/function.hpp>

namespace rstudio {
namespace core {
   class FilePath;
   class Error;
}
}

namespace rstudio {
namespace session {
namespace modules {
namespace rmarkdown {
namespace notebook {

core::Error beginWidgetCapture(
              const core::FilePath& outputFolder,
              const core::FilePath& libraryFolder);

core::Error initHtmlWidgets();

} // namespace notebook
} // namespace rmarkdown
} // namespace modules
} // namespace session
} // namespace rstudio

#endif
