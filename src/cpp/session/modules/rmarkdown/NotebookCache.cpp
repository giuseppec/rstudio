/*
 * NotebookCache.cpp
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

#include "SessionRmdNotebook.hpp"
#include "SessionRnbParser.hpp"
#include "NotebookCache.hpp"
#include "NotebookChunkDefs.hpp"
#include "NotebookPaths.hpp"

#include <boost/foreach.hpp>

#include <session/SessionUserSettings.hpp>
#include <session/SessionModuleContext.hpp>
#include <session/SessionSourceDatabase.hpp>

#include <core/Algorithm.hpp>

#include <r/RExec.hpp>
#include <r/RRoutines.hpp>

// The version identifier for the cache format. Changing this invalidates old
// caches, and should be done only when making breaking changes to the 
// cache format.
#define kCacheVersion "1"

#define kCacheAgeThresholdMs 1000 * 60 * 60 * 24 * 2

using namespace rstudio::core;

namespace rstudio {
namespace session {
namespace modules {
namespace rmarkdown {
namespace notebook {
namespace {

// it's much faster to load a notebook from its cache than it is to rehydrate
// it from its .Rnb, so we keep it around even if the document is closed (as
// it's somewhat common to open and close a document periodically over the 
// course of working on a project, and it's nice when it opens quickly).
//
// however, we don't want to keep the cache around *forever* just in case we
// might need it, as it can be quite large. as a compromise, an unused cache
// hangs around for a couple of days, then gets automatically swept up by this
// function.
void cleanUnusedCaches()
{
   FilePath cacheRoot = notebookCacheRoot();
   if (!cacheRoot.exists())
      return;

   std::vector<FilePath> caches;
   Error error = cacheRoot.children(&caches);
   if (error)
   {
      LOG_ERROR(error);
      return;
   }

   std::string nbCtxId = notebookCtxId();
   BOOST_FOREACH(const FilePath cache, caches)
   {
      // make sure this looks like a notebook cache
      if (!cache.isDirectory())
         continue;
      std::vector<std::string> parts = core::algorithm::split(
            cache.stem(), "-");
      if (parts.size() != 3)
         continue;

      // ignore caches from other contexts
      if (parts[0] != nbCtxId)
         continue;

      // get the path of the notebook associated with the cache
      FilePath path;
      error = notebookIdToPath(parts[1], nbCtxId, &path);
      if (error)
      {
         LOG_ERROR(error);
         continue;
      }

      // is this document still open? if so, leave the cache alone.
      std::string id;
      source_database::getId(module_context::createAliasedPath(
               FileInfo(path)), &id);
      if (!id.empty())
      {
         continue;
      }

      // check the write time on the chunk defs file (updated when the doc is
      // mutated or saved)
      FilePath chunkDefs = cache.complete(kCacheVersion)
                                .complete(kNotebookChunkDefFilename);
      if (!chunkDefs.exists())
         continue;
      if ((std::time(NULL) - chunkDefs.lastWriteTime()) > kCacheAgeThresholdMs) 
      {
         // the cache is old and the document hasn't been opened in a while --
         // remove it.
         continue;
         error = cache.remove();
         if (error)
            LOG_ERROR(error);
      }
   }
}

void onDocRemoved(const std::string& docId, const std::string& docPath)
{
   Error error;

   FilePath cacheFolder = chunkCacheFolder(docPath, docId);
   FilePath defFile = chunkDefinitionsPath(docPath, docId, 
         notebookCtxId());
   if (!docPath.empty() && defFile.exists())
   {
      // for saved documents, we want to keep the cache folder around even when
      // the document is closed, but only if the chunk definitions aren't out
      // of sync.
      FilePath docFile = module_context::resolveAliasedPath(docPath);
      std::time_t writeTime;
      error = getChunkDefs(docPath, docId, &writeTime, NULL);

      if (writeTime <= docFile.lastWriteTime())
      {
         // the doc has been saved since the last time the chunks defs were
         // updated, so no work to do here
         return;
      }
   }
   error = cacheFolder.removeIfExists();
   if (error)
      LOG_ERROR(error);
}

void onDocRenamed(const std::string& oldPath, 
                  boost::shared_ptr<source_database::SourceDocument> pDoc)
{
   Error error;
   bool removeOldDir = false;

   // compute cache folders and ignore if we can't safely adjust them
   FilePath oldCacheDir = chunkCacheFolder(oldPath, pDoc->id());
   FilePath newCacheDir = chunkCacheFolder(pDoc->path(), pDoc->id());
   if (!oldCacheDir.exists() || newCacheDir.exists())
      return;

   // if the doc was previously unsaved, we can just move the whole folder 
   // to its newly saved location
   if (oldPath.empty())
   {
      error = oldCacheDir.move(newCacheDir);
      if (error) 
      {
         // if we can't move the cache to the new location, we'll fall back to
         // copy/remove
         removeOldDir = true;
      }
      else
         return;
   }

   error = oldCacheDir.copyDirectoryRecursive(newCacheDir);
   if (error)
   {
      LOG_ERROR(error);
   }
   else if (removeOldDir) 
   {
      // remove old dir if we couldn't move the folder above
      error = oldCacheDir.remove();
      if (error)
         LOG_ERROR(error);
   }
}

void onDocAdded(const std::string& id)
{
   std::string path;
   Error error = source_database::getPath(id, &path);
   if (error)
   {
      LOG_ERROR(error);
      return;
   }

   // ignore empty paths and non-R Markdown files
   if (path.empty())
      return;
   FilePath docPath = module_context::resolveAliasedPath(path);
   if (docPath.extensionLowerCase() != ".rmd")
      return;

   FilePath cachePath = chunkCacheFolder(path, id);
   FilePath nbPath = docPath.parent().complete(docPath.stem() + ".Rnb");

   // clean up incompatible cache versions (as we're about to invalidate them
   // by mutating the document without updating them) 
   if (cachePath.parent().exists())
   {
      std::vector<FilePath> versions;
      cachePath.parent().children(&versions);
      BOOST_FOREACH(const FilePath& version, versions)
      {
         if (version.isDirectory() && version.filename() != kCacheVersion)
         {
            error = version.remove();
            if (error)
               LOG_ERROR(error);
         }
      }
   }

   if (!cachePath.exists() && nbPath.exists())
   {
      // we have a saved representation, but no cache -- populate the cache
      // from the saved representation
      error = parseRnb(nbPath, cachePath);
      if (error)
      {
         LOG_ERROR(error);
         return;
      }
   }

   // TODO: consider write times of document, cache, and .Rnb -- are there
   // combinations which would suggest we should overwrite the cache with the
   // contents of the notebook?
}

FilePath unsavedNotebookCache()
{
   return module_context::sessionScratchPath().childPath("unsaved-notebooks");
}

SEXP rs_populateNotebookCache(SEXP fileSEXP)
{
   std::string file = r::sexp::safeAsString(fileSEXP);
   FilePath cacheFolder = 
      chunkCacheFolder(file, "", notebookCtxId());
   Error error = parseRnb(module_context::resolveAliasedPath(file), 
                          cacheFolder);
   if (error) 
      LOG_ERROR(error);

   r::sexp::Protect rProtect;
   return r::sexp::create(cacheFolder.absolutePath(), &rProtect);
}

SEXP rs_chunkCacheFolder(SEXP fileSEXP)
{
   std::string file = r::sexp::safeAsString(fileSEXP);
   FilePath cacheFolder =
         chunkCacheFolder(file, "", userSettings().contextId());
   
   r::sexp::Protect protect;
   return r::sexp::create(cacheFolder.absolutePath(), &protect);
}

} // anonymous namespace

FilePath notebookCacheRoot()
{ 
   return module_context::sharedScratchPath().childPath("notebooks");
}

FilePath chunkCacheFolder(const std::string& docPath, const std::string& docId,
      const std::string& nbCtxId)
{
   FilePath folder;
   std::string stem;

   if (docPath.empty()) 
   {
      // the doc hasn't been saved, so keep its chunk output in the scratch
      // path
      folder = unsavedNotebookCache().childPath(docId);
   }
   else
   {
      // the doc has been saved, so keep its chunk output alongside the doc
      // itself
      FilePath path = module_context::resolveAliasedPath(docPath);

      std::string id;
      Error error = notebookPathToId(path, nbCtxId, &id);
      if (error)
         LOG_ERROR(error);
      
      folder = notebookCacheRoot().childPath(nbCtxId + "-" + id + "-" +
            path.stem());
   }

   return folder.childPath(kCacheVersion);
}

FilePath chunkCacheFolder(const std::string& docPath, const std::string& docId)
{
   return chunkCacheFolder(docPath, docId, notebookCtxId());
}

Error initCache()
{
   source_database::events().onDocRenamed.connect(onDocRenamed);
   source_database::events().onDocRemoved.connect(onDocRemoved);
   source_database::events().onDocAdded.connect(onDocAdded);

   RS_REGISTER_CALL_METHOD(rs_populateNotebookCache, 1);
   RS_REGISTER_CALL_METHOD(rs_chunkCacheFolder, 1);

   module_context::scheduleDelayedWork(boost::posix_time::seconds(30),
      cleanUnusedCaches, true);

   return Success();
}

} // namespace notebook
} // namespace rmarkdown
} // namespace modules
} // namespace session
} // namespace rstudio

