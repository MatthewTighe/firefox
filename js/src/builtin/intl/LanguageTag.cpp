/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * vim: set ts=8 sts=2 et sw=2 tw=80:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "builtin/intl/LanguageTag.h"

#include "mozilla/intl/Locale.h"
#include "mozilla/Span.h"

#include "builtin/intl/CommonFunctions.h"
#include "builtin/intl/FormatBuffer.h"
#include "builtin/intl/StringAsciiChars.h"
#include "gc/Tracer.h"
#include "vm/JSAtomState.h"
#include "vm/JSContext.h"

#include "vm/JSObject-inl.h"
#include "vm/ObjectOperations-inl.h"

bool js::intl::ParseLocale(JSContext* cx, Handle<JSLinearString*> str,
                           mozilla::intl::Locale& result) {
  if (StringIsAscii(str)) {
    intl::StringAsciiChars chars(str);
    if (!chars.init(cx)) {
      return false;
    }

    if (mozilla::intl::LocaleParser::TryParse(chars, result).isOk()) {
      return true;
    }
  }

  if (UniqueChars localeChars = QuoteString(cx, str, '"')) {
    JS_ReportErrorNumberASCII(cx, GetErrorMessage, nullptr,
                              JSMSG_INVALID_LANGUAGE_TAG, localeChars.get());
  }
  return false;
}

bool js::intl::ParseStandaloneLanguageTag(
    Handle<JSLinearString*> str, mozilla::intl::LanguageSubtag& result) {
  // Tell the analysis the |IsStructurallyValidLanguageTag| function can't GC.
  JS::AutoSuppressGCAnalysis nogc;

  if (str->hasLatin1Chars()) {
    if (!mozilla::intl::IsStructurallyValidLanguageTag<Latin1Char>(
            str->latin1Range(nogc))) {
      return false;
    }
    result.Set<Latin1Char>(str->latin1Range(nogc));
  } else {
    if (!mozilla::intl::IsStructurallyValidLanguageTag<char16_t>(
            str->twoByteRange(nogc))) {
      return false;
    }
    result.Set<char16_t>(str->twoByteRange(nogc));
  }
  return true;
}

bool js::intl::ParseStandaloneScriptTag(Handle<JSLinearString*> str,
                                        mozilla::intl::ScriptSubtag& result) {
  // Tell the analysis the |IsStructurallyValidScriptTag| function can't GC.
  JS::AutoSuppressGCAnalysis nogc;

  if (str->hasLatin1Chars()) {
    if (!mozilla::intl::IsStructurallyValidScriptTag<Latin1Char>(
            str->latin1Range(nogc))) {
      return false;
    }
    result.Set<Latin1Char>(str->latin1Range(nogc));
  } else {
    if (!mozilla::intl::IsStructurallyValidScriptTag<char16_t>(
            str->twoByteRange(nogc))) {
      return false;
    }
    result.Set<char16_t>(str->twoByteRange(nogc));
  }
  return true;
}

bool js::intl::ParseStandaloneRegionTag(Handle<JSLinearString*> str,
                                        mozilla::intl::RegionSubtag& result) {
  // Tell the analysis the |IsStructurallyValidRegionTag| function can't GC.
  JS::AutoSuppressGCAnalysis nogc;

  if (str->hasLatin1Chars()) {
    if (!mozilla::intl::IsStructurallyValidRegionTag<Latin1Char>(
            str->latin1Range(nogc))) {
      return false;
    }
    result.Set<Latin1Char>(str->latin1Range(nogc));
  } else {
    if (!mozilla::intl::IsStructurallyValidRegionTag<char16_t>(
            str->twoByteRange(nogc))) {
      return false;
    }
    result.Set<char16_t>(str->twoByteRange(nogc));
  }
  return true;
}

template <typename CharT>
static bool ParseStandaloneVariantTag(
    mozilla::Span<const CharT> variantSubtags,
    mozilla::intl::Locale::VariantsVector& result, bool* success) {
  auto isValidVariantSubtag = [&](auto span) {
    // Tell the analysis the |IsStructurallyValidVariantTag| function can't GC.
    JS::AutoSuppressGCAnalysis nogc;
    return mozilla::intl::IsStructurallyValidVariantTag(span);
  };

  size_t start = 0;
  for (size_t index = 0; index < variantSubtags.size(); index++) {
    if (variantSubtags[index] == '-') {
      auto span = variantSubtags.FromTo(start, index);
      if (!isValidVariantSubtag(span)) {
        *success = false;
        return true;
      }

      if (!result.emplaceBack(span)) {
        return false;
      }

      start = index + 1;
    }
  }

  // Trailing variant subtag.
  auto span = variantSubtags.From(start);
  if (!isValidVariantSubtag(span)) {
    *success = false;
    return true;
  }

  if (!result.emplaceBack(span)) {
    return false;
  }

  *success = true;
  return true;
}

bool js::intl::ParseStandaloneVariantTag(
    Handle<JSLinearString*> str, mozilla::intl::Locale::VariantsVector& result,
    bool* success) {
  JS::AutoCheckCannotGC nogc;
  return str->hasLatin1Chars()
             ? ::ParseStandaloneVariantTag(
                   mozilla::Span{str->latin1Range(nogc)}, result, success)
             : ::ParseStandaloneVariantTag(
                   mozilla::Span{str->twoByteRange(nogc)}, result, success);
}

template <typename CharT>
static bool IsAsciiLowercaseAlpha(mozilla::Span<const CharT> span) {
  // Tell the analysis the |std::all_of| function can't GC.
  JS::AutoSuppressGCAnalysis nogc;

  const CharT* ptr = span.data();
  size_t length = span.size();
  return std::all_of(ptr, ptr + length, mozilla::IsAsciiLowercaseAlpha<CharT>);
}

static bool IsAsciiLowercaseAlpha(const JSLinearString* str) {
  JS::AutoCheckCannotGC nogc;
  if (str->hasLatin1Chars()) {
    return IsAsciiLowercaseAlpha<JS::Latin1Char>(str->latin1Range(nogc));
  }
  return IsAsciiLowercaseAlpha<char16_t>(str->twoByteRange(nogc));
}

template <typename CharT>
static bool IsAsciiAlpha(mozilla::Span<const CharT> span) {
  // Tell the analysis the |std::all_of| function can't GC.
  JS::AutoSuppressGCAnalysis nogc;

  const CharT* ptr = span.data();
  size_t length = span.size();
  return std::all_of(ptr, ptr + length, mozilla::IsAsciiAlpha<CharT>);
}

static bool IsAsciiAlpha(const JSLinearString* str) {
  JS::AutoCheckCannotGC nogc;
  if (str->hasLatin1Chars()) {
    return IsAsciiAlpha<JS::Latin1Char>(str->latin1Range(nogc));
  }
  return IsAsciiAlpha<char16_t>(str->twoByteRange(nogc));
}

JS::Result<JSString*> js::intl::ParseStandaloneISO639LanguageTag(
    JSContext* cx, Handle<JSLinearString*> str) {
  // ISO-639 language codes contain either two or three characters.
  size_t length = str->length();
  if (length != 2 && length != 3) {
    return nullptr;
  }

  // We can directly the return the input below if it's in the correct case.
  bool isLowerCase = IsAsciiLowercaseAlpha(str);
  if (!isLowerCase) {
    // Must be an ASCII alpha string.
    if (!IsAsciiAlpha(str)) {
      return nullptr;
    }
  }

  mozilla::intl::LanguageSubtag languageTag;
  if (str->hasLatin1Chars()) {
    JS::AutoCheckCannotGC nogc;
    languageTag.Set<Latin1Char>(str->latin1Range(nogc));
  } else {
    JS::AutoCheckCannotGC nogc;
    languageTag.Set<char16_t>(str->twoByteRange(nogc));
  }

  if (!isLowerCase) {
    // The language subtag is canonicalized to lower case.
    languageTag.ToLowerCase();
  }

  // Reject the input if the canonical tag contains more than just a single
  // language subtag.
  if (mozilla::intl::Locale::ComplexLanguageMapping(languageTag)) {
    return nullptr;
  }

  // Take care to replace deprecated subtags with their preferred values.
  JSString* result;
  if (mozilla::intl::Locale::LanguageMapping(languageTag) || !isLowerCase) {
    result = NewStringCopy<CanGC>(cx, languageTag.Span());
  } else {
    result = str;
  }
  if (!result) {
    return cx->alreadyReportedOOM();
  }
  return result;
}

JS::UniqueChars js::intl::FormatLocale(
    JSContext* cx, JS::Handle<JSObject*> internals,
    JS::HandleVector<UnicodeExtensionKeyword> keywords) {
  RootedValue value(cx);
  if (!GetProperty(cx, internals, internals, cx->names().locale, &value)) {
    return nullptr;
  }

  mozilla::intl::Locale tag;
  {
    Rooted<JSLinearString*> locale(cx, value.toString()->ensureLinear(cx));
    if (!locale) {
      return nullptr;
    }

    if (!ParseLocale(cx, locale, tag)) {
      return nullptr;
    }
  }

  // |ApplyUnicodeExtensionToTag| applies the new keywords to the front of
  // the Unicode extension subtag. We're then relying on ICU to follow RFC
  // 6067, which states that any trailing keywords using the same key
  // should be ignored.
  if (!ApplyUnicodeExtensionToTag(cx, tag, keywords)) {
    return nullptr;
  }

  FormatBuffer<char> buffer(cx);
  if (auto result = tag.ToString(buffer); result.isErr()) {
    ReportInternalError(cx, result.unwrapErr());
    return nullptr;
  }
  return buffer.extractStringZ();
}

void js::intl::UnicodeExtensionKeyword::trace(JSTracer* trc) {
  TraceRoot(trc, &type_, "UnicodeExtensionKeyword::type");
}
