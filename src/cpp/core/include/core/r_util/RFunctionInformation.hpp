/*
 * RFunctionInformation.hpp
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
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

#ifndef CORE_R_UTIL_R_FUNCTION_INFORMATION_HPP
#define CORE_R_UTIL_R_FUNCTION_INFORMATION_HPP

// Utility classes used for collecting information about R packages
// (their functions and what they do)

#include <vector>
#include <string>
#include <map>

#include <boost/optional.hpp>
#include <boost/logic/tribool.hpp>

namespace rstudio {
namespace core {
namespace r_util {

struct FormalInformation
{
   // default ctor -- must be initialized with a name
   explicit FormalInformation(const std::string& name)
      : name(name)
   {}
   
   std::string name;
   boost::optional<std::string> defaultValue;
   boost::tribool hasDefault;
   boost::tribool isUsed;
   boost::tribool missingnessHandled;
};

class FunctionInformation
{
public:
   
   FunctionInformation()
      : noSuchFormal_("")
   {}
   
   void addFormal(const std::string& name)
   {
      formals_.push_back(FormalInformation(name));
      formalNames_.push_back(name);
   }
   
   void addFormal(const FormalInformation& info)
   {
      formals_.push_back(info);
      formalNames_.push_back(info.name);
   }
   
   bool isPrimitive()
   {
      return isPrimitive_ == true;
   }
   
   void setIsPrimitive(bool isPrimitive)
   {
      isPrimitive_ = isPrimitive;
   }
   
   const std::vector<FormalInformation>& formals() const
   {
      return formals_;
   }
   
   std::vector<FormalInformation>& formals()
   {
      return formals_;
   }
   
   
   const std::vector<std::string>& getFormalNames() const
   {
      return formalNames_;
   }
   
   boost::optional<std::string>& defaultValueForFormal(
         const std::string& formalName)
   {
      return infoForFormal(formalName).defaultValue;
   }
   
   FormalInformation& infoForFormal(const std::string& formalName)
   {
      std::size_t n = formals_.size();
      for (std::size_t i = 0; i < n; ++i)
         if (formals_[i].name == formalName)
            return formals_[i];
      
      LOG_WARNING_MESSAGE("No such formal '" + formalName + "'");
      return noSuchFormal_;
   }
   
   void setPerformsNse(bool performsNse) { performsNse_ = performsNse; }
   boost::tribool performsNse() const { return performsNse_; }
   
private:
   std::vector<FormalInformation> formals_;
   std::vector<std::string> formalNames_;
   boost::optional<std::string> originalBindingName_;
   boost::tribool isPrimitive_;
   boost::tribool performsNse_;
   FormalInformation noSuchFormal_;
};

typedef std::string FunctionName;
typedef std::map<FunctionName, FunctionInformation> FunctionInformationMap;

struct PackageInformation
{
   std::string package;
   std::vector<std::string> exports;
   std::vector<int> types;
   FunctionInformationMap functionInfo;
};

inline std::map<std::string, std::vector<std::string> > infoToFormalMap(
      const std::map<std::string, FunctionInformation>& info)
{
   std::map<std::string, std::vector<std::string> > result;
   typedef std::map<std::string, FunctionInformation>::const_iterator const_iterator;
   for (const_iterator it = info.begin(); it != info.end(); ++it)
      result[it->first] = it->second.getFormalNames();
   return result;
}

} // namespace r_util
} // namespace core
} // namespace rstudio

#endif // CORE_R_UTIL_R_FUNCTION_INFORMATION_HPP