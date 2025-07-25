/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef mozilla_GlobalStyleSheetCache_h__
#define mozilla_GlobalStyleSheetCache_h__

#include "mozilla/Attributes.h"
#include "mozilla/BuiltInStyleSheets.h"
#include "mozilla/MemoryReporting.h"
#include "mozilla/NotNull.h"
#include "mozilla/PreferenceSheet.h"
#include "mozilla/StaticPtr.h"
#include "mozilla/css/Loader.h"
#include "mozilla/ipc/SharedMemoryHandle.h"
#include "mozilla/ipc/SharedMemoryMapping.h"
#include "nsIMemoryReporter.h"
#include "nsIObserver.h"

class nsIFile;
class nsIURI;

namespace mozilla {
class CSSStyleSheet;
}  // namespace mozilla

namespace mozilla {
namespace css {

// Enum defining how error should be handled.
enum FailureAction { eCrash = 0, eLogToConsole };

}  // namespace css

class GlobalStyleSheetCache final : public nsIObserver,
                                    public nsIMemoryReporter {
 public:
  NS_DECL_ISUPPORTS
  NS_DECL_NSIOBSERVER
  NS_DECL_NSIMEMORYREPORTER

  static GlobalStyleSheetCache* Singleton();

#define STYLE_SHEET(identifier_, url_, flags_)           \
  NotNull<StyleSheet*> identifier_##Sheet() {            \
    return BuiltInSheet(BuiltInStyleSheet::identifier_); \
  }
#include "mozilla/BuiltInStyleSheetList.h"
#undef STYLE_SHEET

  NotNull<StyleSheet*> BuiltInSheet(BuiltInStyleSheet);

  StyleSheet* GetUserContentSheet();
  StyleSheet* GetUserChromeSheet();

  static void Shutdown();

  static void SetUserContentCSSURL(nsIURI* aURI);

  size_t SizeOfIncludingThis(MallocSizeOf aMallocSizeOf) const;

  // Set the shared memory segment to load the shared UA sheets from.
  // Called early on in a content process' life from
  // ContentChild::InitSharedUASheets, before the GlobalStyleSheetCache
  // singleton has been created.
  static void SetSharedMemory(mozilla::ipc::ReadOnlySharedMemoryHandle aHandle,
                              uintptr_t aAddress);

  // Obtain a shared memory handle for the shared UA sheets to pass into a
  // content process.  Called by ContentParent::InitInternal shortly after
  // a content process has been created.
  mozilla::ipc::ReadOnlySharedMemoryHandle CloneHandle();

  // Returns the address of the shared memory segment that holds the shared UA
  // sheets.
  uintptr_t GetSharedMemoryAddress() {
    return sSharedMemory.IsEmpty() ? 0 : uintptr_t(sSharedMemory.data());
  }

  // Size of the shared memory buffer we'll create to store the shared UA
  // sheets.  We choose a value that is big enough on both 64 bit and 32 bit.
  //
  // If this isn't big enough for the current contents of the shared UA
  // sheets, we'll crash under InitSharedSheetsInParent.
  static constexpr size_t kSharedMemorySize = 1024 * 450;

 private:
  // Shared memory header.
  struct Header {
    static constexpr uint32_t kMagic = 0x55415353;
    uint32_t mMagic;  // Must be set to kMagic.
    const StyleLockedCssRules* mSheets[size_t(BuiltInStyleSheet::Count)];
    uint8_t mBuffer[1];
  };

  GlobalStyleSheetCache();
  ~GlobalStyleSheetCache();

  void InitFromProfile();
  void InitSharedSheetsInParent();
  void InitMemoryReporter();
  RefPtr<StyleSheet> LoadSheetURL(const nsACString& aURL,
                                  css::SheetParsingMode aParsingMode,
                                  css::FailureAction aFailureAction);
  RefPtr<StyleSheet> LoadSheetFile(nsIFile* aFile,
                                   css::SheetParsingMode aParsingMode);
  RefPtr<StyleSheet> LoadSheet(nsIURI* aURI, css::SheetParsingMode aParsingMode,
                               css::FailureAction aFailureAction);
  void LoadSheetFromSharedMemory(const nsACString& aURL,
                                 RefPtr<StyleSheet>* aSheet,
                                 css::SheetParsingMode, const Header*,
                                 BuiltInStyleSheet);

  static StaticRefPtr<GlobalStyleSheetCache> gStyleCache;
  static StaticRefPtr<css::Loader> gCSSLoader;
  static StaticRefPtr<nsIURI> gUserContentSheetURL;

  EnumeratedArray<BuiltInStyleSheet, RefPtr<StyleSheet>,
                  size_t(BuiltInStyleSheet::Count)>
      mBuiltIns;

  RefPtr<StyleSheet> mUserChromeSheet;
  RefPtr<StyleSheet> mUserContentSheet;

  // Shared memory segment storing shared style sheets.
  static mozilla::ipc::shared_memory::LeakedReadOnlyMapping sSharedMemory;

  // How much of the shared memory buffer we ended up using.  Used for memory
  // reporting in the parent process.
  static size_t sUsedSharedMemory;
};

}  // namespace mozilla

#endif
