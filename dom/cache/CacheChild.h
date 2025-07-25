/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef mozilla_dom_cache_CacheChild_h
#define mozilla_dom_cache_CacheChild_h

#include "mozilla/dom/cache/ActorChild.h"
#include "mozilla/dom/cache/PCacheChild.h"
#include "mozilla/dom/cache/CacheOpChild.h"

class nsIAsyncInputStream;
class nsIGlobalObject;

namespace mozilla::dom::cache {
class Cache;
class CacheOpArgs;
class Listener;

class CacheChild final : public PCacheChild, public CacheActorChild {
  friend class PCacheChild;

 public:
  friend class mozilla::detail::BaseAutoLock<CacheChild&>;
  using AutoLock = mozilla::detail::BaseAutoLock<CacheChild&>;

  explicit CacheChild(ActorChild* aParentActor = nullptr);

  void SetListener(CacheChildListener* aListener);

  // Must be called by the associated Cache listener in its OnActorDestroy()
  // method.  Also, Cache must call StartDestroyFromListener() on the actor in
  // its destructor to trigger ActorDestroy() if it has not been called yet.
  void ClearListener();

  template <typename PromiseType>
  void ExecuteOp(nsIGlobalObject* aGlobal, PromiseType& aPromise,
                 nsISupports* aParent, const CacheOpArgs& aArgs) {
    MOZ_ALWAYS_TRUE(SendPCacheOpConstructor(
        new CacheOpChild(GetWorkerRefPtr().clonePtr(), aGlobal, aParent,
                         aPromise, this),
        aArgs));
  }

  // Our parent Listener object has gone out of scope and is being destroyed.
  void StartDestroyFromListener();
  void NoteDeletedActor() override;

  NS_INLINE_DECL_REFCOUNTING(CacheChild, override);

 private:
  ~CacheChild();

  void DestroyInternal();
  // ActorChild methods
  // WorkerRef is trying to destroy due to worker shutdown.
  virtual void StartDestroy() override;

  // PCacheChild methods
  virtual void ActorDestroy(ActorDestroyReason aReason) override;

  already_AddRefed<PCacheOpChild> AllocPCacheOpChild(
      const CacheOpArgs& aOpArgs);

  // utility methods
  inline uint32_t NumChildActors() { return ManagedPCacheOpChild().Count(); }

  // Methods used to temporarily force the actor alive.  Only called from
  // AutoLock.
  void Lock();

  void Unlock();

  ActorChild* mParentActor;
  // Use a weak ref so actor does not hold DOM object alive past content use.
  // The Cache object must call ClearListener() to null this before its
  // destroyed.
  CacheChildListener* MOZ_NON_OWNING_REF mListener;

  bool mLocked;
  bool mDelayedDestroy;
};

}  // namespace mozilla::dom::cache

#endif  // mozilla_dom_cache_CacheChild_h
