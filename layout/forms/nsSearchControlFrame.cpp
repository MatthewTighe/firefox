/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "nsSearchControlFrame.h"

#include "mozilla/PresShell.h"
#include "nsContentUtils.h"
#include "nsGkAtoms.h"

using namespace mozilla;

nsIFrame* NS_NewSearchControlFrame(PresShell* aPresShell,
                                   ComputedStyle* aStyle) {
  return new (aPresShell)
      nsSearchControlFrame(aStyle, aPresShell->GetPresContext());
}

NS_IMPL_FRAMEARENA_HELPERS(nsSearchControlFrame)

NS_QUERYFRAME_HEAD(nsSearchControlFrame)
  NS_QUERYFRAME_ENTRY(nsSearchControlFrame)
NS_QUERYFRAME_TAIL_INHERITING(nsTextControlFrame)

nsSearchControlFrame::nsSearchControlFrame(ComputedStyle* aStyle,
                                           nsPresContext* aPresContext)
    : nsTextControlFrame(aStyle, aPresContext, kClassID) {}

nsresult nsSearchControlFrame::CreateAnonymousContent(
    nsTArray<ContentInfo>& aElements) {
  // We create an anonymous tree for our input element that is structured as
  // follows:
  //
  // input
  //   div    - placeholder
  //   div    - preview div
  //   div    - editor root
  //   button - clear button
  //
  // If you change this, be careful to change the order of stuff in
  // AppendAnonymousContentTo.

  nsTextControlFrame::CreateAnonymousContent(aElements);

  // Create the ::-moz-search-clear-button pseudo-element:
  mButton = MakeAnonElement(PseudoStyleType::mozSearchClearButton, nullptr,
                            nsGkAtoms::button);
  mButton->SetAttr(kNameSpaceID_None, nsGkAtoms::tabindex, u"-1"_ns, false);
  mButton->SetAttr(kNameSpaceID_None, nsGkAtoms::aria_hidden, u"true"_ns,
                   false);
  mButton->SetAttr(kNameSpaceID_None, nsGkAtoms::title, u""_ns, false);
  aElements.AppendElement(mButton);

  return NS_OK;
}
