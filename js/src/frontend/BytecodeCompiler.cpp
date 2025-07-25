/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * vim: set ts=8 sts=2 et sw=2 tw=80:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "frontend/BytecodeCompiler.h"

#include "mozilla/Attributes.h"
#include "mozilla/Maybe.h"
#include "mozilla/Utf8.h"     // mozilla::Utf8Unit
#include "mozilla/Variant.h"  // mozilla::Variant

#include "debugger/DebugAPI.h"
#include "ds/LifoAlloc.h"
#include "frontend/BytecodeEmitter.h"
#include "frontend/CompilationStencil.h"  // ExtensibleCompilationStencil, ExtraBindingInfoVector, CompilationInput, CompilationGCOutput
#include "frontend/EitherParser.h"
#include "frontend/FrontendContext.h"  // AutoReportFrontendContext
#include "frontend/ModuleSharedContext.h"
#include "frontend/ParserAtom.h"     // ParserAtomsTable, TaggedParserAtomIndex
#include "frontend/SharedContext.h"  // SharedContext, GlobalSharedContext
#include "frontend/Stencil.h"        // ParserBindingIter
#include "frontend/UsedNameTracker.h"  // UsedNameTracker, UsedNameMap
#include "js/AllocPolicy.h"        // js::SystemAllocPolicy, ReportOutOfMemory
#include "js/CharacterEncoding.h"  // JS_EncodeStringToUTF8
#include "js/ColumnNumber.h"       // JS::ColumnNumberOneOrigin
#include "js/EnvironmentChain.h"   // JS::SupportUnscopables
#include "js/ErrorReport.h"        // JS_ReportErrorASCII
#include "js/experimental/CompileScript.h"  // JS::CompileGlobalScriptToStencil, JS::CompileModuleScriptToStencil
#include "js/experimental/JSStencil.h"
#include "js/GCVector.h"    // JS::StackGCVector
#include "js/Id.h"          // JS::PropertyKey
#include "js/Modules.h"     // JS::ImportAssertionVector
#include "js/RootingAPI.h"  // JS::Handle, JS::MutableHandle
#include "js/SourceText.h"  // JS::SourceText
#include "js/UniquePtr.h"
#include "js/Utility.h"                // UniqueChars
#include "js/Value.h"                  // JS::Value
#include "vm/EnvironmentObject.h"      // WithEnvironmentObject
#include "vm/FunctionFlags.h"          // FunctionFlags
#include "vm/GeneratorAndAsyncKind.h"  // js::GeneratorKind, js::FunctionAsyncKind
#include "vm/HelperThreads.h"  // StartOffThreadDelazification, WaitForAllDelazifyTasks
#include "vm/JSContext.h"      // JSContext
#include "vm/JSObject.h"       // SetIntegrityLevel, IntegrityLevel
#include "vm/JSScript.h"       // ScriptSource, UncompressedSourceCache
#include "vm/ModuleBuilder.h"  // js::ModuleBuilder
#include "vm/NativeObject.h"   // NativeDefineDataProperty
#include "vm/PlainObject.h"    // NewPlainObjectWithProto
#include "vm/Time.h"           // AutoIncrementalTimer
#include "wasm/AsmJS.h"

#include "vm/Compartment-inl.h"  // JS::Compartment::wrap
#include "vm/GeckoProfiler-inl.h"
#include "vm/JSContext-inl.h"
#include "vm/JSObject-inl.h"  // JSObject::maybeHasInterestingSymbolProperty for ObjectOperations-inl.h
#include "vm/ObjectOperations-inl.h"  // HasProperty

using namespace js;
using namespace js::frontend;

using mozilla::Maybe;
using mozilla::Utf8Unit;

using JS::CompileOptions;
using JS::ReadOnlyCompileOptions;
using JS::SourceText;

// RAII class to check the frontend reports an exception when it fails to
// compile a script.
class MOZ_RAII AutoAssertReportedException {
#ifdef DEBUG
  JSContext* maybeCx_;
  FrontendContext* fc_;
  bool check_;

 public:
  explicit AutoAssertReportedException(JSContext* maybeCx, FrontendContext* fc)
      : maybeCx_(maybeCx), fc_(fc), check_(true) {}
  void reset() { check_ = false; }
  ~AutoAssertReportedException() {
    if (!check_) {
      return;
    }

    // Error while compiling self-hosted code isn't set as an exception.
    // TODO: Remove this once all errors are added to frontend context.
    if (maybeCx_ && !maybeCx_->runtime()->hasInitializedSelfHosting()) {
      return;
    }

    // TODO: Remove this once JSContext is removed from frontend.
    if (maybeCx_) {
      MOZ_ASSERT(maybeCx_->isExceptionPending() || fc_->hadErrors());
    } else {
      MOZ_ASSERT(fc_->hadErrors());
    }
  }
#else
 public:
  explicit AutoAssertReportedException(JSContext*, FrontendContext*) {}
  void reset() {}
#endif
};

static bool EmplaceEmitter(CompilationState& compilationState,
                           Maybe<BytecodeEmitter>& emitter, FrontendContext* fc,
                           const EitherParser& parser, SharedContext* sc);

template <typename Unit>
class MOZ_STACK_CLASS SourceAwareCompiler {
 protected:
  SourceText<Unit>& sourceBuffer_;

  CompilationState compilationState_;

  Maybe<Parser<SyntaxParseHandler, Unit>> syntaxParser;
  Maybe<Parser<FullParseHandler, Unit>> parser;
  FrontendContext* fc_ = nullptr;

  using TokenStreamPosition = frontend::TokenStreamPosition<Unit>;

 protected:
  explicit SourceAwareCompiler(FrontendContext* fc,
                               LifoAllocScope& parserAllocScope,
                               CompilationInput& input,
                               SourceText<Unit>& sourceBuffer)
      : sourceBuffer_(sourceBuffer),
        compilationState_(fc, parserAllocScope, input) {
    MOZ_ASSERT(sourceBuffer_.get() != nullptr);
  }

  [[nodiscard]] bool init(FrontendContext* fc, ScopeBindingCache* scopeCache,
                          InheritThis inheritThis = InheritThis::No,
                          JSObject* enclosingEnv = nullptr) {
    if (!compilationState_.init(fc, scopeCache, inheritThis, enclosingEnv)) {
      return false;
    }

    return createSourceAndParser(fc);
  }

  // Call this before calling compile{Global,Eval}Script.
  [[nodiscard]] bool createSourceAndParser(FrontendContext* fc);

  void assertSourceAndParserCreated() const {
    MOZ_ASSERT(compilationState_.source != nullptr);
    MOZ_ASSERT(parser.isSome());
  }

  void assertSourceParserAndScriptCreated() { assertSourceAndParserCreated(); }

  [[nodiscard]] bool emplaceEmitter(Maybe<BytecodeEmitter>& emitter,
                                    SharedContext* sharedContext) {
    return EmplaceEmitter(compilationState_, emitter, fc_,
                          EitherParser(parser.ptr()), sharedContext);
  }

  bool canHandleParseFailure(const Directives& newDirectives);

  void handleParseFailure(
      const Directives& newDirectives, TokenStreamPosition& startPosition,
      CompilationState::CompilationStatePosition& startStatePosition);

 public:
  CompilationState& compilationState() { return compilationState_; };

  ExtensibleCompilationStencil& stencil() { return compilationState_; }
};

template <typename Unit>
class MOZ_STACK_CLASS ScriptCompiler : public SourceAwareCompiler<Unit> {
  using Base = SourceAwareCompiler<Unit>;

 protected:
  using Base::compilationState_;
  using Base::parser;
  using Base::sourceBuffer_;

  using Base::assertSourceParserAndScriptCreated;
  using Base::canHandleParseFailure;
  using Base::emplaceEmitter;
  using Base::handleParseFailure;

  using typename Base::TokenStreamPosition;

 public:
  explicit ScriptCompiler(FrontendContext* fc, LifoAllocScope& parserAllocScope,
                          CompilationInput& input,
                          SourceText<Unit>& sourceBuffer)
      : Base(fc, parserAllocScope, input, sourceBuffer) {}

  using Base::init;
  using Base::stencil;

  [[nodiscard]] bool compile(JSContext* cx, SharedContext* sc);

 private:
  [[nodiscard]] bool popupateExtraBindingsFields(GlobalSharedContext* globalsc);
};

static already_AddRefed<JS::Stencil> CreateInitialStencilAndDelazifications(
    FrontendContext* fc, CompilationStencil* initial) {
  RefPtr stencils =
      fc->getAllocator()->new_<frontend::InitialStencilAndDelazifications>();
  if (!stencils) {
    return nullptr;
  }
  if (!stencils->init(fc, initial)) {
    return nullptr;
  }
  return stencils.forget();
}

using BytecodeCompilerOutput =
    mozilla::Variant<RefPtr<CompilationStencil>, CompilationGCOutput*>;

static bool ConvertGlobalScriptStencilMaybeInstantiate(
    JSContext* maybeCx, FrontendContext* fc, CompilationInput& input,
    ExtensibleCompilationStencil&& extensibleStencil,
    CompilationStencil** initialStencilOut,
    InitialStencilAndDelazifications** stencilsOut,
    CompilationGCOutput* gcOutput) {
  RefPtr<CompilationStencil> initialStencil;
  if (input.options.populateDelazificationCache() || initialStencilOut ||
      stencilsOut) {
    auto extensibleStencilOnHeap =
        fc->getAllocator()->make_unique<frontend::ExtensibleCompilationStencil>(
            std::move(extensibleStencil));
    if (!extensibleStencilOnHeap) {
      return false;
    }

    initialStencil = fc->getAllocator()->new_<CompilationStencil>(
        std::move(extensibleStencilOnHeap));
    if (!initialStencil) {
      return false;
    }

    if (initialStencilOut) {
      *initialStencilOut = initialStencil.get();
      (*initialStencilOut)->AddRef();
    }
  }

  RefPtr<InitialStencilAndDelazifications> stencils;
  if (input.options.populateDelazificationCache() || stencilsOut) {
    stencils = CreateInitialStencilAndDelazifications(fc, initialStencil.get());
    if (!stencils) {
      return false;
    }

    if (stencilsOut) {
      *stencilsOut = stencils.get();
      (*stencilsOut)->AddRef();
    }
  }

  if (input.options.populateDelazificationCache()) {
    // NOTE: Delazification can be triggered from off-thread compilation.
    StartOffThreadDelazification(maybeCx, input.options, stencils.get());

    // When we are trying to validate whether on-demand delazification
    // generate the same stencil as concurrent delazification, we want to
    // parse everything eagerly off-thread ahead of re-parsing everything on
    // demand, to compare the outcome.
    //
    // This option works only from main-thread compilation, to avoid
    // dead-lock.
    if (input.options.waitForDelazificationCache() && maybeCx) {
      WaitForAllDelazifyTasks(maybeCx->runtime());
    }
  }

  if (gcOutput) {
    MOZ_ASSERT(maybeCx);
    if (stencils) {
      if (!InstantiateStencils(maybeCx, input, *stencils.get(), *gcOutput)) {
        return false;
      }
    } else {
      MOZ_ASSERT(!initialStencilOut);
      BorrowingCompilationStencil borrowingStencil(extensibleStencil);
      if (!InstantiateStencils(maybeCx, input, borrowingStencil, *gcOutput)) {
        return false;
      }
    }
  }

  return true;
}

static constexpr ExtraBindingInfoVector* NoExtraBindings = nullptr;
static constexpr CompilationStencil** NoInitialStencilOut = nullptr;
static constexpr InitialStencilAndDelazifications** NoStencilsOut = nullptr;
static constexpr CompilationGCOutput* NoGCOutput = nullptr;

// Compile global script, and return it as one of:
//   * ExtensibleCompilationStencil (without instantiation)
//   * CompilationStencil (without instantiation, has no external dependency)
//   * CompilationGCOutput (with instantiation).
template <typename Unit>
[[nodiscard]] static bool CompileGlobalScriptToStencilAndMaybeInstantiate(
    JSContext* maybeCx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache,
    JS::SourceText<Unit>& srcBuf, ScopeKind scopeKind,
    ExtraBindingInfoVector* maybeExtraBindings,
    CompilationStencil** initialStencilOut,
    InitialStencilAndDelazifications** stencilsOut,
    CompilationGCOutput* gcOutput) {
  if (input.options.selfHostingMode) {
    if (!input.initForSelfHostingGlobal(fc)) {
      return false;
    }
  } else if (maybeExtraBindings) {
    if (!input.initForGlobalWithExtraBindings(fc, maybeExtraBindings)) {
      return false;
    }
  } else {
    if (!input.initForGlobal(fc)) {
      return false;
    }
  }

  AutoAssertReportedException assertException(maybeCx, fc);

  LifoAllocScope parserAllocScope(&tempLifoAlloc);
  ScriptCompiler<Unit> compiler(fc, parserAllocScope, input, srcBuf);
  if (!compiler.init(fc, scopeCache)) {
    return false;
  }

  SourceExtent extent = SourceExtent::makeGlobalExtent(
      srcBuf.length(), input.options.lineno,
      JS::LimitedColumnNumberOneOrigin::fromUnlimited(
          JS::ColumnNumberOneOrigin(input.options.column)));

  GlobalSharedContext globalsc(fc, scopeKind, input.options,
                               compiler.compilationState().directives, extent);

  if (!compiler.compile(maybeCx, &globalsc)) {
    return false;
  }

  if (!ConvertGlobalScriptStencilMaybeInstantiate(
          maybeCx, fc, input, std::move(compiler.stencil()), initialStencilOut,
          stencilsOut, gcOutput)) {
    return false;
  }

  assertException.reset();
  return true;
}

template <typename Unit>
static already_AddRefed<CompilationStencil>
CompileGlobalScriptToStencilWithInputImpl(
    JSContext* maybeCx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache,
    JS::SourceText<Unit>& srcBuf, ScopeKind scopeKind) {
  RefPtr<CompilationStencil> stencil;
  if (!CompileGlobalScriptToStencilAndMaybeInstantiate(
          maybeCx, fc, tempLifoAlloc, input, scopeCache, srcBuf, scopeKind,
          NoExtraBindings, getter_AddRefs(stencil), NoGCOutput)) {
    return nullptr;
  }
  return stencil.forget();
}

already_AddRefed<CompilationStencil>
frontend::CompileGlobalScriptToStencilWithInput(
    JSContext* cx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache,
    JS::SourceText<Utf8Unit>& srcBuf, ScopeKind scopeKind) {
  RefPtr<CompilationStencil> stencil;
  if (!CompileGlobalScriptToStencilAndMaybeInstantiate(
          cx, fc, tempLifoAlloc, input, scopeCache, srcBuf, scopeKind,
          NoExtraBindings, getter_AddRefs(stencil), NoStencilsOut,
          NoGCOutput)) {
    return nullptr;
  }
  return stencil.forget();
}

template <typename CharT>
static already_AddRefed<JS::Stencil> CompileGlobalScriptToStencilImpl(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<CharT>& srcBuf) {
  ScopeKind scopeKind =
      options.nonSyntacticScope ? ScopeKind::NonSyntactic : ScopeKind::Global;

  AutoReportFrontendContext fc(cx);

  NoScopeBindingCache scopeCache;
  Rooted<CompilationInput> input(cx, CompilationInput(options));
  RefPtr<InitialStencilAndDelazifications> stencils;
  if (!CompileGlobalScriptToStencilAndMaybeInstantiate(
          cx, &fc, cx->tempLifoAlloc(), input.get(), &scopeCache, srcBuf,
          scopeKind, NoExtraBindings, NoInitialStencilOut,
          getter_AddRefs(stencils), NoGCOutput)) {
    return nullptr;
  }
  return stencils.forget();
}

already_AddRefed<JS::Stencil> JS::CompileGlobalScriptToStencil(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<mozilla::Utf8Unit>& srcBuf) {
  return CompileGlobalScriptToStencilImpl(cx, options, srcBuf);
}

already_AddRefed<JS::Stencil> JS::CompileGlobalScriptToStencil(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf) {
  return CompileGlobalScriptToStencilImpl(cx, options, srcBuf);
}

template <typename CharT>
static already_AddRefed<JS::Stencil> CompileGlobalScriptToStencilImpl(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<CharT>& srcBuf) {
  ScopeKind scopeKind =
      options.nonSyntacticScope ? ScopeKind::NonSyntactic : ScopeKind::Global;

  NoScopeBindingCache scopeCache;
  js::LifoAlloc tempLifoAlloc(JSContext::TEMP_LIFO_ALLOC_PRIMARY_CHUNK_SIZE,
                              js::BackgroundMallocArena);
  CompilationInput compilationInput(options);
  RefPtr<InitialStencilAndDelazifications> stencils;
  if (!CompileGlobalScriptToStencilAndMaybeInstantiate(
          nullptr, fc, tempLifoAlloc, compilationInput, &scopeCache, srcBuf,
          scopeKind, NoExtraBindings, NoInitialStencilOut,
          getter_AddRefs(stencils), NoGCOutput)) {
    JS_HAZ_VALUE_IS_GC_SAFE(compilationInput);
    return nullptr;
  }
  // CompilationInput initialized with CompileGlobalScriptToStencil only
  // references information from the JS::Stencil context and the
  // ref-counted ScriptSource, which are both GC-free.
  JS_HAZ_VALUE_IS_GC_SAFE(compilationInput);
  return stencils.forget();
}

already_AddRefed<JS::Stencil> JS::CompileGlobalScriptToStencil(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<mozilla::Utf8Unit>& srcBuf) {
#ifdef DEBUG
  fc->assertNativeStackLimitThread();
#endif
  return CompileGlobalScriptToStencilImpl(fc, options, srcBuf);
}

already_AddRefed<JS::Stencil> JS::CompileGlobalScriptToStencil(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf) {
#ifdef DEBUG
  fc->assertNativeStackLimitThread();
#endif
  return CompileGlobalScriptToStencilImpl(fc, options, srcBuf);
}

static void FireOnNewScript(JSContext* cx,
                            const JS::InstantiateOptions& options,
                            JS::Handle<JSScript*> script) {
  if (!options.hideFromNewScriptInitial()) {
    DebugAPI::onNewScript(cx, script);
  }
}

static inline ScriptSource* getSource(const CompilationStencil& stencil) {
  return stencil.source;
}

static inline ScriptSource* getSource(
    const InitialStencilAndDelazifications& stencils) {
  return stencils.getInitial()->source;
}

template <typename T>
bool InstantiateStencilsImpl(JSContext* cx, CompilationInput& input, T& stencil,
                             CompilationGCOutput& gcOutput) {
  {
    AutoGeckoProfilerEntry pseudoFrame(cx, "stencil instantiate",
                                       JS::ProfilingCategoryPair::JS_Parsing);

    if (!T::instantiateStencils(cx, input, stencil, gcOutput)) {
      return false;
    }
  }

  // Enqueue an off-thread source compression task after finishing parsing.
  if (!getSource(stencil)->tryCompressOffThread(cx)) {
    return false;
  }

  Rooted<JSScript*> script(cx, gcOutput.script);
  const JS::InstantiateOptions instantiateOptions(input.options);
  FireOnNewScript(cx, instantiateOptions, script);

  return true;
}

bool frontend::InstantiateStencils(JSContext* cx, CompilationInput& input,
                                   const CompilationStencil& stencil,
                                   CompilationGCOutput& gcOutput) {
  return InstantiateStencilsImpl(cx, input, stencil, gcOutput);
}

bool frontend::InstantiateStencils(JSContext* cx, CompilationInput& input,
                                   InitialStencilAndDelazifications& stencils,
                                   CompilationGCOutput& gcOutput) {
  return InstantiateStencilsImpl(cx, input, stencils, gcOutput);
}

template <typename Unit>
static JSScript* CompileGlobalScriptImpl(
    JSContext* cx, FrontendContext* fc,
    const JS::ReadOnlyCompileOptions& options, JS::SourceText<Unit>& srcBuf,
    ScopeKind scopeKind, ExtraBindingInfoVector* maybeExtraBindings) {
  Rooted<CompilationInput> input(cx, CompilationInput(options));
  Rooted<CompilationGCOutput> gcOutput(cx);
  NoScopeBindingCache scopeCache;
  if (!CompileGlobalScriptToStencilAndMaybeInstantiate(
          cx, fc, cx->tempLifoAlloc(), input.get(), &scopeCache, srcBuf,
          scopeKind, maybeExtraBindings, NoInitialStencilOut, NoStencilsOut,
          gcOutput.address())) {
    return nullptr;
  }
  return gcOutput.get().script;
}

JSScript* frontend::CompileGlobalScript(
    JSContext* cx, FrontendContext* fc,
    const JS::ReadOnlyCompileOptions& options, JS::SourceText<char16_t>& srcBuf,
    ScopeKind scopeKind) {
  return CompileGlobalScriptImpl(cx, fc, options, srcBuf, scopeKind,
                                 NoExtraBindings);
}

static bool CreateExtraBindingInfoVector(
    JSContext* cx,
    JS::Handle<JS::StackGCVector<JS::PropertyKey>> unwrappedBindingKeys,
    JS::Handle<JS::StackGCVector<JS::Value>> unwrappedBindingValues,
    ExtraBindingInfoVector& extraBindings) {
  MOZ_ASSERT(unwrappedBindingKeys.length() == unwrappedBindingValues.length());

  if (!extraBindings.reserve(unwrappedBindingKeys.length())) {
    ReportOutOfMemory(cx);
    return false;
  }

  JS::Rooted<JSObject*> globalLexical(cx, &cx->global()->lexicalEnvironment());
  JS::Rooted<JS::PropertyKey> id(cx);
  for (size_t i = 0; i < unwrappedBindingKeys.length(); i++) {
    if (!unwrappedBindingKeys[i].isString()) {
      JS_ReportErrorASCII(cx, "The bindings key should be a string.");
      return false;
    }

    JS::Rooted<JSString*> str(cx, unwrappedBindingKeys[i].toString());

    UniqueChars utf8chars = JS_EncodeStringToUTF8(cx, str);
    if (!utf8chars) {
      return false;
    }

    bool isShadowed = false;

    id = unwrappedBindingKeys[i];
    cx->markId(id);

    bool found;
    if (!HasProperty(cx, cx->global(), id, &found)) {
      return false;
    }
    if (found) {
      isShadowed = true;
    } else {
      if (!HasProperty(cx, globalLexical, id, &found)) {
        return false;
      }
      if (found) {
        isShadowed = true;
      }
    }

    extraBindings.infallibleEmplaceBack(std::move(utf8chars), isShadowed);
  }

  return true;
}

static WithEnvironmentObject* CreateExtraBindingsEnvironment(
    JSContext* cx,
    JS::Handle<JS::StackGCVector<JS::PropertyKey>> unwrappedBindingKeys,
    JS::Handle<JS::StackGCVector<JS::Value>> unwrappedBindingValues,
    const ExtraBindingInfoVector& extraBindings) {
  JS::Rooted<PlainObject*> extraBindingsObj(
      cx, NewPlainObjectWithProto(cx, nullptr));
  if (!extraBindingsObj) {
    return nullptr;
  }

  MOZ_ASSERT(unwrappedBindingKeys.length() == extraBindings.length());

  JS::Rooted<JS::PropertyKey> id(cx);
  size_t i = 0;
  for (const auto& bindingInfo : extraBindings) {
    if (bindingInfo.isShadowed) {
      i++;
      continue;
    }

    id = unwrappedBindingKeys[i];
    cx->markId(id);
    JS::Rooted<JS::Value> val(cx, unwrappedBindingValues[i]);
    if (!cx->compartment()->wrap(cx, &val) ||
        !NativeDefineDataProperty(cx, extraBindingsObj, id, val, 0)) {
      return nullptr;
    }
    i++;
  }

  // The list of bindings shouldn't be modified.
  if (!SetIntegrityLevel(cx, extraBindingsObj, IntegrityLevel::Sealed)) {
    return nullptr;
  }

  JS::Rooted<JSObject*> globalLexical(cx, &cx->global()->lexicalEnvironment());
  return WithEnvironmentObject::createNonSyntactic(
      cx, extraBindingsObj, globalLexical, JS::SupportUnscopables::No);
}

JSScript* frontend::CompileGlobalScriptWithExtraBindings(
    JSContext* cx, FrontendContext* fc,
    const JS::ReadOnlyCompileOptions& options, JS::SourceText<char16_t>& srcBuf,
    JS::Handle<JS::StackGCVector<JS::PropertyKey>> unwrappedBindingKeys,
    JS::Handle<JS::StackGCVector<JS::Value>> unwrappedBindingValues,
    JS::MutableHandle<JSObject*> env) {
  ExtraBindingInfoVector extraBindings;
  if (!CreateExtraBindingInfoVector(cx, unwrappedBindingKeys,
                                    unwrappedBindingValues, extraBindings)) {
    return nullptr;
  }

  JS::Rooted<JSScript*> script(
      cx, CompileGlobalScriptImpl(cx, fc, options, srcBuf,
                                  ScopeKind::NonSyntactic, &extraBindings));
  if (!script) {
    if (fc->extraBindingsAreNotUsed()) {
      // Compile the script as regular global script in global lexical.

      fc->clearNoExtraBindingReferencesFound();

      // Warnings can be reported. Clear them to avoid reporting twice.
      fc->clearWarnings();

      // No other error should be reported.
      MOZ_ASSERT(!fc->hadErrors());
      MOZ_ASSERT(!cx->isExceptionPending());

      env.set(&cx->global()->lexicalEnvironment());

      JS::CompileOptions copiedOptions(nullptr, options);
      copiedOptions.setNonSyntacticScope(false);

      return CompileGlobalScript(cx, fc, copiedOptions, srcBuf,
                                 ScopeKind::Global);
    }

    return nullptr;
  }

  WithEnvironmentObject* extraBindingsEnv = CreateExtraBindingsEnvironment(
      cx, unwrappedBindingKeys, unwrappedBindingValues, extraBindings);
  if (!extraBindingsEnv) {
    return nullptr;
  }

  env.set(extraBindingsEnv);

  return script;
}

JSScript* frontend::CompileGlobalScript(
    JSContext* cx, FrontendContext* fc,
    const JS::ReadOnlyCompileOptions& options, JS::SourceText<Utf8Unit>& srcBuf,
    ScopeKind scopeKind) {
  return CompileGlobalScriptImpl(cx, fc, options, srcBuf, scopeKind,
                                 NoExtraBindings);
}

template <typename Unit>
static JSScript* CompileEvalScriptImpl(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    SourceText<Unit>& srcBuf, JS::Handle<js::Scope*> enclosingScope,
    JS::Handle<JSObject*> enclosingEnv) {
  JS::Rooted<JSScript*> script(cx);
  {
    AutoReportFrontendContext fc(cx);
    AutoAssertReportedException assertException(cx, &fc);

    Rooted<CompilationInput> input(cx, CompilationInput(options));
    if (!input.get().initForEval(&fc, enclosingScope)) {
      return nullptr;
    }

    LifoAllocScope parserAllocScope(&cx->tempLifoAlloc());

    ScopeBindingCache* scopeCache = &cx->caches().scopeCache;
    ScriptCompiler<Unit> compiler(&fc, parserAllocScope, input.get(), srcBuf);
    if (!compiler.init(&fc, scopeCache, InheritThis::Yes, enclosingEnv)) {
      return nullptr;
    }

    uint32_t len = srcBuf.length();
    SourceExtent extent = SourceExtent::makeGlobalExtent(
        len, options.lineno,
        JS::LimitedColumnNumberOneOrigin::fromUnlimited(
            JS::ColumnNumberOneOrigin(options.column)));
    EvalSharedContext evalsc(&fc, compiler.compilationState(), extent);
    if (!compiler.compile(cx, &evalsc)) {
      return nullptr;
    }

    Rooted<CompilationGCOutput> gcOutput(cx);
    {
      BorrowingCompilationStencil borrowingStencil(compiler.stencil());
      if (!InstantiateStencils(cx, input.get(), borrowingStencil,
                               gcOutput.get())) {
        return nullptr;
      }
    }

    assertException.reset();
    script = gcOutput.get().script;
  }
  return script;
}

JSScript* frontend::CompileEvalScript(JSContext* cx,
                                      const JS::ReadOnlyCompileOptions& options,
                                      JS::SourceText<char16_t>& srcBuf,
                                      JS::Handle<js::Scope*> enclosingScope,
                                      JS::Handle<JSObject*> enclosingEnv) {
  return CompileEvalScriptImpl(cx, options, srcBuf, enclosingScope,
                               enclosingEnv);
}

template <typename Unit>
class MOZ_STACK_CLASS ModuleCompiler final : public SourceAwareCompiler<Unit> {
  using Base = SourceAwareCompiler<Unit>;

  using Base::assertSourceParserAndScriptCreated;
  using Base::compilationState_;
  using Base::emplaceEmitter;
  using Base::parser;

 public:
  explicit ModuleCompiler(FrontendContext* fc, LifoAllocScope& parserAllocScope,
                          CompilationInput& input,
                          SourceText<Unit>& sourceBuffer)
      : Base(fc, parserAllocScope, input, sourceBuffer) {}

  using Base::init;
  using Base::stencil;

  [[nodiscard]] bool compile(JSContext* maybeCx, FrontendContext* fc);
};

template <typename Unit>
class MOZ_STACK_CLASS StandaloneFunctionCompiler final
    : public SourceAwareCompiler<Unit> {
  using Base = SourceAwareCompiler<Unit>;

  using Base::assertSourceAndParserCreated;
  using Base::canHandleParseFailure;
  using Base::compilationState_;
  using Base::emplaceEmitter;
  using Base::handleParseFailure;
  using Base::parser;
  using Base::sourceBuffer_;

  using typename Base::TokenStreamPosition;

 public:
  explicit StandaloneFunctionCompiler(FrontendContext* fc,
                                      LifoAllocScope& parserAllocScope,
                                      CompilationInput& input,
                                      SourceText<Unit>& sourceBuffer)
      : Base(fc, parserAllocScope, input, sourceBuffer) {}

  using Base::init;
  using Base::stencil;

 private:
  FunctionNode* parse(JSContext* cx, FunctionSyntaxKind syntaxKind,
                      GeneratorKind generatorKind, FunctionAsyncKind asyncKind,
                      const Maybe<uint32_t>& parameterListEnd);

 public:
  [[nodiscard]] bool compile(JSContext* cx, FunctionSyntaxKind syntaxKind,
                             GeneratorKind generatorKind,
                             FunctionAsyncKind asyncKind,
                             const Maybe<uint32_t>& parameterListEnd);
};

template <typename Unit>
bool SourceAwareCompiler<Unit>::createSourceAndParser(FrontendContext* fc) {
  const auto& options = compilationState_.input.options;

  fc_ = fc;

  if (!compilationState_.source->assignSource(fc, options, sourceBuffer_)) {
    return false;
  }

  MOZ_ASSERT(compilationState_.canLazilyParse ==
             CanLazilyParse(compilationState_.input.options));
  if (compilationState_.canLazilyParse) {
    syntaxParser.emplace(fc_, options, sourceBuffer_.units(),
                         sourceBuffer_.length(), compilationState_,
                         /* syntaxParser = */ nullptr);
    if (!syntaxParser->checkOptions()) {
      return false;
    }
  }

  parser.emplace(fc_, options, sourceBuffer_.units(), sourceBuffer_.length(),
                 compilationState_, syntaxParser.ptrOr(nullptr));
  parser->ss = compilationState_.source.get();
  return parser->checkOptions();
}

static bool EmplaceEmitter(CompilationState& compilationState,
                           Maybe<BytecodeEmitter>& emitter, FrontendContext* fc,
                           const EitherParser& parser, SharedContext* sc) {
  BytecodeEmitter::EmitterMode emitterMode =
      sc->selfHosted() ? BytecodeEmitter::SelfHosting : BytecodeEmitter::Normal;
  emitter.emplace(fc, parser, sc, compilationState, emitterMode);
  return emitter->init();
}

template <typename Unit>
bool SourceAwareCompiler<Unit>::canHandleParseFailure(
    const Directives& newDirectives) {
  // Try to reparse if no parse errors were thrown and the directives changed.
  //
  // NOTE:
  // Only the following two directive changes force us to reparse the script:
  // - The "use asm" directive was encountered.
  // - The "use strict" directive was encountered and duplicate parameter names
  //   are present. We reparse in this case to display the error at the correct
  //   source location. See |Parser::hasValidSimpleStrictParameterNames()|.
  return !parser->anyChars.hadError() &&
         compilationState_.directives != newDirectives;
}

template <typename Unit>
void SourceAwareCompiler<Unit>::handleParseFailure(
    const Directives& newDirectives, TokenStreamPosition& startPosition,
    CompilationState::CompilationStatePosition& startStatePosition) {
  MOZ_ASSERT(canHandleParseFailure(newDirectives));

  // Rewind to starting position to retry.
  parser->tokenStream.rewind(startPosition);
  compilationState_.rewind(startStatePosition);

  // Assignment must be monotonic to prevent reparsing iloops
  MOZ_ASSERT_IF(compilationState_.directives.strict(), newDirectives.strict());
  MOZ_ASSERT_IF(compilationState_.directives.asmJS(), newDirectives.asmJS());
  compilationState_.directives = newDirectives;
}

static bool UsesExtraBindings(GlobalSharedContext* globalsc,
                              const ExtraBindingInfoVector& extraBindings,
                              const UsedNameTracker::UsedNameMap& usedNameMap) {
  for (const auto& bindingInfo : extraBindings) {
    if (bindingInfo.isShadowed) {
      continue;
    }

    for (auto r = usedNameMap.all(); !r.empty(); r.popFront()) {
      const auto& item = r.front();
      const auto& name = item.key();
      if (bindingInfo.nameIndex != name) {
        continue;
      }

      const auto& nameInfo = item.value();
      if (nameInfo.empty()) {
        continue;
      }

      // This name is free, and uses the extra binding.
      return true;
    }
  }

  return false;
}

template <typename Unit>
bool ScriptCompiler<Unit>::popupateExtraBindingsFields(
    GlobalSharedContext* globalsc) {
  if (!compilationState_.input.internExtraBindings(
          this->fc_, compilationState_.parserAtoms)) {
    return false;
  }

  bool hasNonShadowedBinding = false;
  for (auto& bindingInfo : compilationState_.input.extraBindings()) {
    if (bindingInfo.isShadowed) {
      continue;
    }

    bool isShadowed = false;

    if (globalsc->bindings) {
      for (ParserBindingIter bi(*globalsc->bindings); bi; bi++) {
        if (bindingInfo.nameIndex == bi.name()) {
          isShadowed = true;
          break;
        }
      }
    }

    bindingInfo.isShadowed = isShadowed;
    if (!isShadowed) {
      hasNonShadowedBinding = true;
    }
  }

  if (!hasNonShadowedBinding) {
    // All bindings are shadowed.
    this->fc_->reportExtraBindingsAreNotUsed();
    return false;
  }

  if (globalsc->hasDirectEval()) {
    // Direct eval can contain reference.
    return true;
  }

  if (!UsesExtraBindings(globalsc, compilationState_.input.extraBindings(),
                         parser->usedNames().map())) {
    this->fc_->reportExtraBindingsAreNotUsed();
    return false;
  }

  return true;
}

template <typename Unit>
bool ScriptCompiler<Unit>::compile(JSContext* maybeCx, SharedContext* sc) {
  assertSourceParserAndScriptCreated();

  TokenStreamPosition startPosition(parser->tokenStream);

  // Emplace the topLevel stencil
  MOZ_ASSERT(compilationState_.scriptData.length() ==
             CompilationStencil::TopLevelIndex);
  if (!compilationState_.appendScriptStencilAndData(sc->fc_)) {
    return false;
  }

  ParseNode* pn;
  {
    Maybe<AutoGeckoProfilerEntry> pseudoFrame;
    if (maybeCx) {
      pseudoFrame.emplace(maybeCx, "script parsing",
                          JS::ProfilingCategoryPair::JS_Parsing);
    }
    if (sc->isEvalContext()) {
      pn = parser->evalBody(sc->asEvalContext()).unwrapOr(nullptr);
    } else {
      pn = parser->globalBody(sc->asGlobalContext()).unwrapOr(nullptr);
    }
  }

  if (!pn) {
    // Global and eval scripts don't get reparsed after a new directive was
    // encountered:
    // - "use strict" doesn't require any special error reporting for scripts.
    // - "use asm" directives don't have an effect in global/eval contexts.
    MOZ_ASSERT(!canHandleParseFailure(compilationState_.directives));
    return false;
  }

  if (sc->isGlobalContext() && compilationState_.input.hasExtraBindings()) {
    if (!popupateExtraBindingsFields(sc->asGlobalContext())) {
      return false;
    }
  }

  {
    // Successfully parsed. Emit the script.
    Maybe<AutoGeckoProfilerEntry> pseudoFrame;
    if (maybeCx) {
      pseudoFrame.emplace(maybeCx, "script emit",
                          JS::ProfilingCategoryPair::JS_Parsing);
    }

    Maybe<BytecodeEmitter> emitter;
    if (!emplaceEmitter(emitter, sc)) {
      return false;
    }

    if (!emitter->emitScript(pn)) {
      return false;
    }
  }

  MOZ_ASSERT(!this->fc_->hadErrors());

  return true;
}

template <typename Unit>
bool ModuleCompiler<Unit>::compile(JSContext* maybeCx, FrontendContext* fc) {
  // Emplace the topLevel stencil
  MOZ_ASSERT(compilationState_.scriptData.length() ==
             CompilationStencil::TopLevelIndex);
  if (!compilationState_.appendScriptStencilAndData(fc)) {
    return false;
  }

  ModuleBuilder builder(fc, parser.ptr());

  const auto& options = compilationState_.input.options;

  uint32_t len = this->sourceBuffer_.length();
  SourceExtent extent = SourceExtent::makeGlobalExtent(
      len, options.lineno,
      JS::LimitedColumnNumberOneOrigin::fromUnlimited(
          JS::ColumnNumberOneOrigin(options.column)));
  ModuleSharedContext modulesc(fc, options, builder, extent);

  ParseNode* pn = parser->moduleBody(&modulesc).unwrapOr(nullptr);
  if (!pn) {
    return false;
  }

  Maybe<BytecodeEmitter> emitter;
  if (!emplaceEmitter(emitter, &modulesc)) {
    return false;
  }

  if (!emitter->emitScript(pn->as<ModuleNode>().body())) {
    return false;
  }

  StencilModuleMetadata& moduleMetadata = *compilationState_.moduleMetadata;

  builder.finishFunctionDecls(moduleMetadata);

  MOZ_ASSERT(!this->fc_->hadErrors());

  return true;
}

// Parse a standalone JS function, which might appear as the value of an
// event handler attribute in an HTML <INPUT> tag, or in a Function()
// constructor.
template <typename Unit>
FunctionNode* StandaloneFunctionCompiler<Unit>::parse(
    JSContext* cx, FunctionSyntaxKind syntaxKind, GeneratorKind generatorKind,
    FunctionAsyncKind asyncKind, const Maybe<uint32_t>& parameterListEnd) {
  assertSourceAndParserCreated();

  TokenStreamPosition startPosition(parser->tokenStream);
  auto startStatePosition = compilationState_.getPosition();

  // Speculatively parse using the default directives implied by the context.
  // If a directive is encountered (e.g., "use strict") that changes how the
  // function should have been parsed, we backup and reparse with the new set
  // of directives.

  FunctionNode* fn;
  for (;;) {
    Directives newDirectives = compilationState_.directives;
    fn = parser
             ->standaloneFunction(parameterListEnd, syntaxKind, generatorKind,
                                  asyncKind, compilationState_.directives,
                                  &newDirectives)
             .unwrapOr(nullptr);
    if (fn) {
      break;
    }

    // Maybe we encountered a new directive. See if we can try again.
    if (!canHandleParseFailure(newDirectives)) {
      return nullptr;
    }

    handleParseFailure(newDirectives, startPosition, startStatePosition);
  }

  return fn;
}

// Compile a standalone JS function.
template <typename Unit>
bool StandaloneFunctionCompiler<Unit>::compile(
    JSContext* cx, FunctionSyntaxKind syntaxKind, GeneratorKind generatorKind,
    FunctionAsyncKind asyncKind, const Maybe<uint32_t>& parameterListEnd) {
  FunctionNode* parsedFunction =
      parse(cx, syntaxKind, generatorKind, asyncKind, parameterListEnd);
  if (!parsedFunction) {
    return false;
  }

  FunctionBox* funbox = parsedFunction->funbox();

  if (funbox->isInterpreted()) {
    Maybe<BytecodeEmitter> emitter;
    if (!emplaceEmitter(emitter, funbox)) {
      return false;
    }

    if (!emitter->emitFunctionScript(parsedFunction)) {
      return false;
    }

    // The parser extent has stripped off the leading `function...` but
    // we want the SourceExtent used in the final standalone script to
    // start from the beginning of the buffer, and use the provided
    // line and column.
    const auto& options = compilationState_.input.options;
    compilationState_.scriptExtra[CompilationStencil::TopLevelIndex].extent =
        SourceExtent{/* sourceStart = */ 0,
                     sourceBuffer_.length(),
                     funbox->extent().toStringStart,
                     funbox->extent().toStringEnd,
                     options.lineno,
                     JS::LimitedColumnNumberOneOrigin::fromUnlimited(
                         JS::ColumnNumberOneOrigin(options.column))};
  } else {
    // The asm.js module was created by parser. Instantiation below will
    // allocate the JSFunction that wraps it.
    MOZ_ASSERT(funbox->isAsmJSModule());
    MOZ_ASSERT(compilationState_.asmJS->moduleMap.has(funbox->index()));
    MOZ_ASSERT(compilationState_.scriptData[CompilationStencil::TopLevelIndex]
                   .functionFlags.isAsmJSNative());
  }

  return true;
}

// Compile module, and return it as one of:
//   * ExtensibleCompilationStencil (without instantiation)
//   * CompilationStencil (without instantiation, has no external dependency)
//   * CompilationGCOutput (with instantiation).
template <typename Unit>
[[nodiscard]] static bool ParseModuleToStencilAndMaybeInstantiate(
    JSContext* maybeCx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache,
    SourceText<Unit>& srcBuf, BytecodeCompilerOutput& output) {
  MOZ_ASSERT(srcBuf.get());
  MOZ_ASSERT(input.options.lineno != 0,
             "Module cannot be compiled with lineNumber == 0");

  if (!input.initForModule(fc)) {
    return false;
  }

  AutoAssertReportedException assertException(maybeCx, fc);

  LifoAllocScope parserAllocScope(&tempLifoAlloc);
  ModuleCompiler<Unit> compiler(fc, parserAllocScope, input, srcBuf);
  if (!compiler.init(fc, scopeCache)) {
    return false;
  }

  if (!compiler.compile(maybeCx, fc)) {
    return false;
  }

  if (output.is<RefPtr<CompilationStencil>>()) {
    Maybe<AutoGeckoProfilerEntry> pseudoFrame;
    if (maybeCx) {
      pseudoFrame.emplace(maybeCx, "script emit",
                          JS::ProfilingCategoryPair::JS_Parsing);
    }

    auto extensibleStencil =
        fc->getAllocator()->make_unique<frontend::ExtensibleCompilationStencil>(
            std::move(compiler.stencil()));
    if (!extensibleStencil) {
      return false;
    }

    RefPtr<CompilationStencil> stencil =
        fc->getAllocator()->new_<CompilationStencil>(
            std::move(extensibleStencil));
    if (!stencil) {
      return false;
    }

    output.as<RefPtr<CompilationStencil>>() = std::move(stencil);
  } else {
    MOZ_ASSERT(maybeCx);
    BorrowingCompilationStencil borrowingStencil(compiler.stencil());
    if (!InstantiateStencils(maybeCx, input, borrowingStencil,
                             *(output.as<CompilationGCOutput*>()))) {
      return false;
    }
  }

  assertException.reset();
  return true;
}

template <typename Unit>
already_AddRefed<CompilationStencil> ParseModuleToStencilImpl(
    JSContext* maybeCx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache,
    SourceText<Unit>& srcBuf) {
  using OutputType = RefPtr<CompilationStencil>;
  BytecodeCompilerOutput output((OutputType()));
  if (!ParseModuleToStencilAndMaybeInstantiate(
          maybeCx, fc, tempLifoAlloc, input, scopeCache, srcBuf, output)) {
    return nullptr;
  }
  return output.as<OutputType>().forget();
}

template <typename CharT>
static already_AddRefed<JS::Stencil> CompileModuleScriptToStencilImpl(
    JSContext* cx, const JS::ReadOnlyCompileOptions& optionsInput,
    JS::SourceText<CharT>& srcBuf) {
  JS::CompileOptions options(cx, optionsInput);
  options.setModule();

  AutoReportFrontendContext fc(cx);

  NoScopeBindingCache scopeCache;
  Rooted<CompilationInput> input(cx, CompilationInput(options));
  RefPtr<CompilationStencil> stencil = ParseModuleToStencilImpl(
      cx, &fc, cx->tempLifoAlloc(), input.get(), &scopeCache, srcBuf);
  if (!stencil) {
    return nullptr;
  }
  return CreateInitialStencilAndDelazifications(&fc, stencil.get());
}

already_AddRefed<JS::Stencil> JS::CompileModuleScriptToStencil(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<mozilla::Utf8Unit>& srcBuf) {
  return CompileModuleScriptToStencilImpl(cx, options, srcBuf);
}

already_AddRefed<JS::Stencil> JS::CompileModuleScriptToStencil(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf) {
  return CompileModuleScriptToStencilImpl(cx, options, srcBuf);
}

template <typename CharT>
static already_AddRefed<JS::Stencil> CompileModuleScriptToStencilImpl(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& optionsInput,
    JS::SourceText<CharT>& srcBuf) {
  JS::CompileOptions options(nullptr, optionsInput);
  options.setModule();

  frontend::CompilationInput compilationInput(options);

  NoScopeBindingCache scopeCache;
  js::LifoAlloc tempLifoAlloc(JSContext::TEMP_LIFO_ALLOC_PRIMARY_CHUNK_SIZE,
                              js::BackgroundMallocArena);
  RefPtr<CompilationStencil> stencil = ParseModuleToStencilImpl(
      nullptr, fc, tempLifoAlloc, compilationInput, &scopeCache, srcBuf);
  if (!stencil) {
    JS_HAZ_VALUE_IS_GC_SAFE(compilationInput);
    return nullptr;
  }
  // CompilationInput initialized with ParseModuleToStencil only
  // references information from the JS::Stencil context and the
  // ref-counted ScriptSource, which are both GC-free.
  JS_HAZ_VALUE_IS_GC_SAFE(compilationInput);
  return CreateInitialStencilAndDelazifications(fc, stencil.get());
}

already_AddRefed<JS::Stencil> JS::CompileModuleScriptToStencil(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& optionsInput,
    JS::SourceText<mozilla::Utf8Unit>& srcBuf) {
#ifdef DEBUG
  fc->assertNativeStackLimitThread();
#endif
  return CompileModuleScriptToStencilImpl(fc, optionsInput, srcBuf);
}

already_AddRefed<JS::Stencil> JS::CompileModuleScriptToStencil(
    JS::FrontendContext* fc, const JS::ReadOnlyCompileOptions& optionsInput,
    JS::SourceText<char16_t>& srcBuf) {
#ifdef DEBUG
  fc->assertNativeStackLimitThread();
#endif
  return CompileModuleScriptToStencilImpl(fc, optionsInput, srcBuf);
}

template <typename Unit>
static ModuleObject* CompileModuleImpl(
    JSContext* cx, FrontendContext* fc,
    const JS::ReadOnlyCompileOptions& optionsInput, SourceText<Unit>& srcBuf) {
  AutoAssertReportedException assertException(cx, fc);

  CompileOptions options(cx, optionsInput);
  options.setModule();

  Rooted<CompilationInput> input(cx, CompilationInput(options));
  Rooted<CompilationGCOutput> gcOutput(cx);
  BytecodeCompilerOutput output(gcOutput.address());

  NoScopeBindingCache scopeCache;
  if (!ParseModuleToStencilAndMaybeInstantiate(cx, fc, cx->tempLifoAlloc(),
                                               input.get(), &scopeCache, srcBuf,
                                               output)) {
    return nullptr;
  }

  assertException.reset();
  return gcOutput.get().module;
}

ModuleObject* frontend::CompileModule(JSContext* cx, FrontendContext* fc,
                                      const JS::ReadOnlyCompileOptions& options,
                                      SourceText<char16_t>& srcBuf) {
  return CompileModuleImpl(cx, fc, options, srcBuf);
}

ModuleObject* frontend::CompileModule(JSContext* cx, FrontendContext* fc,
                                      const JS::ReadOnlyCompileOptions& options,
                                      SourceText<Utf8Unit>& srcBuf) {
  return CompileModuleImpl(cx, fc, options, srcBuf);
}

static bool InstantiateLazyFunction(JSContext* cx, CompilationInput& input,
                                    const CompilationStencil& stencil) {
  mozilla::DebugOnly<uint32_t> lazyFlags =
      static_cast<uint32_t>(input.immutableFlags());

  Rooted<CompilationGCOutput> gcOutput(cx);

  if (!CompilationStencil::instantiateStencils(cx, input, stencil,
                                               gcOutput.get())) {
    return false;
  }

  // NOTE: After instantiation succeeds and bytecode is attached, the rest of
  //       this operation should be infallible. Any failure during
  //       delazification should restore the function back to a consistent
  //       lazy state.

  MOZ_ASSERT(lazyFlags == gcOutput.get().script->immutableFlags());
  MOZ_ASSERT(gcOutput.get().script->outermostScope()->hasOnChain(
                 ScopeKind::NonSyntactic) ==
             gcOutput.get().script->immutableFlags().hasFlag(
                 JSScript::ImmutableFlags::HasNonSyntacticScope));

  return true;
}

// Compile lazy functinn specified by a pair of `units` + `length`, and
// optionally instantiate.
//
// If `stencils` is provided, the result of delazification is stored into it.
//
// If `borrowOut` is provided, a borrowing pointer is returned.
//
// If `borrowOut` is not provided, the function is instantiated.
// In this case, `maybeCx` should be provided and `input` should be initialized
// with a BaseScript.
template <typename Unit>
static bool CompileLazyFunctionToStencilMaybeInstantiate(
    JSContext* maybeCx, FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    CompilationInput& input, ScopeBindingCache* scopeCache, const Unit* units,
    size_t length, InitialStencilAndDelazifications* stencils,
    const CompilationStencil** borrowOut) {
  MOZ_ASSERT(input.source);

  AutoAssertReportedException assertException(maybeCx, fc);

  InheritThis inheritThis =
      input.functionFlags().isArrow() ? InheritThis::Yes : InheritThis::No;

  LifoAllocScope parserAllocScope(&tempLifoAlloc);
  CompilationState compilationState(fc, parserAllocScope, input);
  compilationState.setFunctionKey(input.extent());
  MOZ_ASSERT(!compilationState.isInitialStencil());
  if (!compilationState.init(fc, scopeCache, inheritThis)) {
    return false;
  }

  Parser<FullParseHandler, Unit> parser(fc, input.options, units, length,
                                        compilationState,
                                        /* syntaxParser = */ nullptr);
  if (!parser.checkOptions()) {
    return false;
  }

  FunctionNode* pn =
      parser
          .standaloneLazyFunction(input, input.extent().toStringStart,
                                  input.strict(), input.generatorKind(),
                                  input.asyncKind())
          .unwrapOr(nullptr);
  if (!pn) {
    return false;
  }

  BytecodeEmitter bce(fc, &parser, pn->funbox(), compilationState,
                      BytecodeEmitter::LazyFunction);
  if (!bce.init(pn->pn_pos)) {
    return false;
  }

  if (!bce.emitFunctionScript(pn)) {
    return false;
  }

  // NOTE: Only allow relazification if there was no lazy PrivateScriptData.
  // This excludes non-leaf functions and all script class constructors.
  bool hadLazyScriptData = input.hasPrivateScriptData();
  bool isRelazifiableAfterDelazify = input.isRelazifiable();
  if (isRelazifiableAfterDelazify && !hadLazyScriptData) {
    compilationState.scriptData[CompilationStencil::TopLevelIndex]
        .setAllowRelazify();
  }

  if (stencils && input.options.checkDelazificationCache()) {
    const CompilationStencil* cached =
        stencils->getDelazificationFor(input.extent());
    if (cached) {
      auto& concurrentSharedData = cached->sharedData;
      auto concurrentData =
          concurrentSharedData.isSingle()
              ? concurrentSharedData.asSingle()->get()->immutableData()
              : concurrentSharedData.asBorrow()
                    ->asSingle()
                    ->get()
                    ->immutableData();
      auto ondemandData =
          compilationState.sharedData.asSingle()->get()->immutableData();
      MOZ_RELEASE_ASSERT(concurrentData.Length() == ondemandData.Length(),
                         "Non-deterministic stencils");
      for (size_t i = 0; i < concurrentData.Length(); i++) {
        MOZ_RELEASE_ASSERT(concurrentData[i] == ondemandData[i],
                           "Non-deterministic stencils");
      }
    }
  }

  if (borrowOut) {
    auto extensibleStencil =
        fc->getAllocator()->make_unique<frontend::ExtensibleCompilationStencil>(
            std::move(compilationState));
    if (!extensibleStencil) {
      return false;
    }

    RefPtr<CompilationStencil> stencil =
        fc->getAllocator()->new_<CompilationStencil>(
            std::move(extensibleStencil));
    if (!stencil) {
      return false;
    }

    *borrowOut = stencils->storeDelazification(std::move(stencil));
  } else {
    MOZ_ASSERT(maybeCx);
    if (stencils) {
      auto extensibleStencil =
          maybeCx->make_unique<frontend::ExtensibleCompilationStencil>(
              std::move(compilationState));
      if (!extensibleStencil) {
        return false;
      }

      RefPtr<CompilationStencil> stencil =
          maybeCx->new_<CompilationStencil>(std::move(extensibleStencil));
      if (!stencil) {
        return false;
      }

      const CompilationStencil* borrowed =
          stencils->storeDelazification(std::move(stencil));

      if (!InstantiateLazyFunction(maybeCx, input, *borrowed)) {
        return false;
      }
    } else {
      BorrowingCompilationStencil borrowingStencil(compilationState);
      if (!InstantiateLazyFunction(maybeCx, input, borrowingStencil)) {
        return false;
      }
    }
  }

  assertException.reset();
  return true;
}

template <typename Unit>
static bool DelazifyCanonicalScriptedFunctionImpl(JSContext* cx,
                                                  FrontendContext* fc,
                                                  ScopeBindingCache* scopeCache,
                                                  JS::Handle<JSFunction*> fun,
                                                  JS::Handle<BaseScript*> lazy,
                                                  ScriptSource* ss) {
  MOZ_ASSERT(!lazy->hasBytecode(), "Script is already compiled!");
  MOZ_ASSERT(lazy->function() == fun);

  MOZ_DIAGNOSTIC_ASSERT(!fun->isGhost());

  AutoIncrementalTimer timer(cx->realm()->timers.delazificationTime);

  JS::CompileOptions options(cx);
  options.setMutedErrors(lazy->mutedErrors())
      .setFileAndLine(lazy->filename(), lazy->lineno())
      .setColumn(JS::ColumnNumberOneOrigin(lazy->column()))
      .setScriptSourceOffset(lazy->sourceStart())
      .setNoScriptRval(false)
      .setSelfHostingMode(false)
      .setEagerDelazificationStrategy(lazy->delazificationMode());

  Rooted<CompilationInput> input(cx, CompilationInput(options));
  input.get().initFromLazy(cx, lazy, ss);

  RefPtr<InitialStencilAndDelazifications> stencils =
      lazy->sourceObject()->maybeGetStencils();

  if (stencils && input.get().options.consumeDelazificationCache()) {
    const CompilationStencil* cached =
        stencils->getDelazificationFor(input.get().extent());
    if (cached) {
      return InstantiateLazyFunction(cx, input.get(), *cached);
    }
  }

  size_t sourceStart = lazy->sourceStart();
  size_t sourceLength = lazy->sourceEnd() - lazy->sourceStart();

  MOZ_ASSERT(ss->hasSourceText());

  // Parse and compile the script from source.
  UncompressedSourceCache::AutoHoldEntry holder;

  MOZ_ASSERT(ss->hasSourceType<Unit>());

  ScriptSource::PinnedUnits<Unit> units(cx, ss, holder, sourceStart,
                                        sourceLength);
  if (!units.get()) {
    return false;
  }

  return CompileLazyFunctionToStencilMaybeInstantiate(
      cx, fc, cx->tempLifoAlloc(), input.get(), scopeCache, units.get(),
      sourceLength, stencils, nullptr);
}

bool frontend::DelazifyCanonicalScriptedFunction(JSContext* cx,
                                                 FrontendContext* fc,
                                                 JS::Handle<JSFunction*> fun) {
  Maybe<AutoGeckoProfilerEntry> pseudoFrame;
  if (cx) {
    pseudoFrame.emplace(cx, "script delazify",
                        JS::ProfilingCategoryPair::JS_Parsing);
  }

  Rooted<BaseScript*> lazy(cx, fun->baseScript());
  ScriptSource* ss = lazy->scriptSource();
  ScopeBindingCache* scopeCache = &cx->caches().scopeCache;

  if (ss->hasSourceType<Utf8Unit>()) {
    // UTF-8 source text.
    return DelazifyCanonicalScriptedFunctionImpl<Utf8Unit>(cx, fc, scopeCache,
                                                           fun, lazy, ss);
  }

  MOZ_ASSERT(ss->hasSourceType<char16_t>());

  // UTF-16 source text.
  return DelazifyCanonicalScriptedFunctionImpl<char16_t>(cx, fc, scopeCache,
                                                         fun, lazy, ss);
}

template <typename Unit>
static const CompilationStencil* DelazifyCanonicalScriptedFunctionImpl(
    FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    const JS::PrefableCompileOptions& prefableOptions,
    ScopeBindingCache* scopeCache, ScriptIndex scriptIndex,
    InitialStencilAndDelazifications* stencils,
    DelazifyFailureReason* failureReason) {
  MOZ_ASSERT(stencils);

  ScriptStencilRef script{*stencils, scriptIndex};
  const CompilationStencil* cached = script.maybeContext();
  if (cached) {
    return cached;
  }

  const ScriptStencilExtra& extra = script.scriptExtra();

#if defined(EARLY_BETA_OR_EARLIER) || defined(DEBUG)
  MOZ_ASSERT(!script.isEagerlyCompiledInInitial(),
             "Script is already compiled in initial stencil!");
  const ScriptStencil& data = script.scriptDataFromEnclosing();
  MOZ_DIAGNOSTIC_ASSERT(!data.isGhost());
  MOZ_DIAGNOSTIC_ASSERT(data.wasEmittedByEnclosingScript());
#endif

  size_t sourceStart = extra.extent.sourceStart;
  size_t sourceLength = extra.extent.sourceEnd - sourceStart;

  ScriptSource* ss = stencils->getInitial()->source;
  MOZ_ASSERT(ss->hasSourceText());

  MOZ_ASSERT(ss->hasSourceType<Unit>());

  ScriptSource::PinnedUnitsIfUncompressed<Unit> units(ss, sourceStart,
                                                      sourceLength);
  if (!units.get()) {
    *failureReason = DelazifyFailureReason::Compressed;
    return nullptr;
  }

  JS::CompileOptions options(prefableOptions);
  options.setMutedErrors(ss->mutedErrors())
      .setFileAndLine(ss->filename(), extra.extent.lineno)
      .setColumn(JS::ColumnNumberOneOrigin(extra.extent.column))
      .setScriptSourceOffset(sourceStart)
      .setNoScriptRval(false)
      .setSelfHostingMode(false);

  // CompilationInput initialized with initFromStencil only reference
  // information from the CompilationStencil context and the ref-counted
  // ScriptSource, which are both GC-free.
  JS_HAZ_NON_GC_POINTER CompilationInput input(options);
  input.initFromStencil(*stencils, scriptIndex, ss);

  const CompilationStencil* borrow;
  if (!CompileLazyFunctionToStencilMaybeInstantiate(
          nullptr, fc, tempLifoAlloc, input, scopeCache, units.get(),
          sourceLength, stencils, &borrow)) {
    *failureReason = DelazifyFailureReason::Other;
    return nullptr;
  }

  return borrow;
}

const CompilationStencil* frontend::DelazifyCanonicalScriptedFunction(
    FrontendContext* fc, js::LifoAlloc& tempLifoAlloc,
    const JS::PrefableCompileOptions& prefableOptions,
    ScopeBindingCache* scopeCache, ScriptIndex scriptIndex,
    InitialStencilAndDelazifications* stencils,
    DelazifyFailureReason* failureReason) {
  ScriptSource* ss = stencils->getInitial()->source;
  if (ss->hasSourceType<Utf8Unit>()) {
    // UTF-8 source text.
    return DelazifyCanonicalScriptedFunctionImpl<Utf8Unit>(
        fc, tempLifoAlloc, prefableOptions, scopeCache, scriptIndex, stencils,
        failureReason);
  }

  // UTF-16 source text.
  MOZ_ASSERT(ss->hasSourceType<char16_t>());
  return DelazifyCanonicalScriptedFunctionImpl<char16_t>(
      fc, tempLifoAlloc, prefableOptions, scopeCache, scriptIndex, stencils,
      failureReason);
}

static JSFunction* CompileStandaloneFunction(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind, GeneratorKind generatorKind,
    FunctionAsyncKind asyncKind, JS::Handle<Scope*> enclosingScope = nullptr) {
  JS::Rooted<JSFunction*> fun(cx);
  {
    AutoReportFrontendContext fc(cx);
    AutoAssertReportedException assertException(cx, &fc);

    Rooted<CompilationInput> input(cx, CompilationInput(options));
    if (enclosingScope) {
      if (!input.get().initForStandaloneFunctionInNonSyntacticScope(
              &fc, enclosingScope)) {
        return nullptr;
      }
    } else {
      if (!input.get().initForStandaloneFunction(cx, &fc)) {
        return nullptr;
      }
    }

    LifoAllocScope parserAllocScope(&cx->tempLifoAlloc());
    InheritThis inheritThis = (syntaxKind == FunctionSyntaxKind::Arrow)
                                  ? InheritThis::Yes
                                  : InheritThis::No;
    ScopeBindingCache* scopeCache = &cx->caches().scopeCache;
    StandaloneFunctionCompiler<char16_t> compiler(&fc, parserAllocScope,
                                                  input.get(), srcBuf);
    if (!compiler.init(&fc, scopeCache, inheritThis)) {
      return nullptr;
    }

    if (!compiler.compile(cx, syntaxKind, generatorKind, asyncKind,
                          parameterListEnd)) {
      return nullptr;
    }

    Rooted<CompilationGCOutput> gcOutput(cx);
    RefPtr<ScriptSource> source;
    {
      BorrowingCompilationStencil borrowingStencil(compiler.stencil());
      if (!CompilationStencil::instantiateStencils(
              cx, input.get(), borrowingStencil, gcOutput.get())) {
        return nullptr;
      }
      source = borrowingStencil.source;
    }

    fun = gcOutput.get().getFunctionNoBaseIndex(
        CompilationStencil::TopLevelIndex);
    MOZ_ASSERT(fun->hasBytecode() || IsAsmJSModule(fun));

    // Enqueue an off-thread source compression task after finishing parsing.
    if (!source->tryCompressOffThread(cx)) {
      return nullptr;
    }

    // Note: If AsmJS successfully compiles, the into.script will still be
    // nullptr. In this case we have compiled to a native function instead of an
    // interpreted script.
    if (gcOutput.get().script) {
      if (parameterListEnd) {
        source->setParameterListEnd(*parameterListEnd);
      }

      const JS::InstantiateOptions instantiateOptions(options);
      Rooted<JSScript*> script(cx, gcOutput.get().script);
      FireOnNewScript(cx, instantiateOptions, script);
    }

    assertException.reset();
  }
  return fun;
}

JSFunction* frontend::CompileStandaloneFunction(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind) {
  return CompileStandaloneFunction(cx, options, srcBuf, parameterListEnd,
                                   syntaxKind, GeneratorKind::NotGenerator,
                                   FunctionAsyncKind::SyncFunction);
}

JSFunction* frontend::CompileStandaloneGenerator(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind) {
  return CompileStandaloneFunction(cx, options, srcBuf, parameterListEnd,
                                   syntaxKind, GeneratorKind::Generator,
                                   FunctionAsyncKind::SyncFunction);
}

JSFunction* frontend::CompileStandaloneAsyncFunction(
    JSContext* cx, const ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind) {
  return CompileStandaloneFunction(cx, options, srcBuf, parameterListEnd,
                                   syntaxKind, GeneratorKind::NotGenerator,
                                   FunctionAsyncKind::AsyncFunction);
}

JSFunction* frontend::CompileStandaloneAsyncGenerator(
    JSContext* cx, const ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind) {
  return CompileStandaloneFunction(cx, options, srcBuf, parameterListEnd,
                                   syntaxKind, GeneratorKind::Generator,
                                   FunctionAsyncKind::AsyncFunction);
}

JSFunction* frontend::CompileStandaloneFunctionInNonSyntacticScope(
    JSContext* cx, const JS::ReadOnlyCompileOptions& options,
    JS::SourceText<char16_t>& srcBuf, const Maybe<uint32_t>& parameterListEnd,
    FunctionSyntaxKind syntaxKind, JS::Handle<Scope*> enclosingScope) {
  MOZ_ASSERT(enclosingScope);
  return CompileStandaloneFunction(cx, options, srcBuf, parameterListEnd,
                                   syntaxKind, GeneratorKind::NotGenerator,
                                   FunctionAsyncKind::SyncFunction,
                                   enclosingScope);
}
